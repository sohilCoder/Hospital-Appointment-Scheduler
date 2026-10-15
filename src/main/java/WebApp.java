import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Web front end for the scheduler: a hospital page and a patient page.
 * Plain HTML forms, no JavaScript, served by the JDK's built-in HTTP server (no libraries).
 *
 * Run:  java -cp out WebApp [port]      then open http://localhost:8080
 *
 * All data lives in memory and is lost when the server stops.
 */
public class WebApp {

    static final String PROVIDER = "Hospital";
    static final String SPECIALTY = "general";
    static final int DAY = Scheduler.DAY;
    static final int BLOCK = Scheduler.BLOCK;
    static final int CALENDAR_DAYS = 14;                       // two weeks, starting today
    static final int[] DURATIONS = {15, 30, 45, 60, 90, 120};
    static final String[] SEVERITY = {"", "Low", "Mild", "Moderate", "High", "Urgent"};
    static final String[] WEEKDAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US);
    static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("EEE M/d", Locale.US);

    static class Account {
        final String username;
        final byte[] salt, passwordHash;   // never store the password itself
        final int createdAt;
        Integer currentRequestId;          // the patient's one active appointment or waitlist spot

        Account(String username, byte[] salt, byte[] passwordHash, int createdAt) {
            this.username = username; this.salt = salt; this.passwordHash = passwordHash; this.createdAt = createdAt;
        }
    }

    static class Session {
        String accountKey;                 // null = not logged in
        String flash;                      // one-time message shown on the next page load
        Map<String, String> draft;         // booking form values to refill after an error
        Map<String, String> pending;       // a booking whose time wasn't open (waitlist or pick another)
        String pendingProblem;
    }

    record Booking(int start, int duration, int severity, String reason) {}

    private final Clock clock;
    private final LocalDate baseMonday;    // scheduler minute 0 = this Monday 00:00, local time
    private final Scheduler scheduler;
    private final Map<Integer, Scheduler.Window> hours = new TreeMap<>();  // weekday -> open hours
    private boolean hoursSet = false;
    private final Map<String, Account> accounts = new HashMap<>();          // lower-case username -> account
    private final Map<String, Session> sessions = new HashMap<>();          // cookie id -> session
    private final SecureRandom random = new SecureRandom();
    private int nextRequestId = 1;
    private String hospitalFlash;

    WebApp(Clock clock) {
        this.clock = clock;
        this.baseMonday = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        this.scheduler = new Scheduler(List.of(new Scheduler.Provider(PROVIDER, SPECIALTY, Map.of())), 1);
        scheduler.setUrgentReserve(false);  // patients pick their own times here
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        WebApp app = new WebApp(Clock.systemDefaultZone());
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.createContext("/", app::handle);
        server.start();
        System.out.println("Clinic scheduler running at http://localhost:" + port);
        System.out.println("  Hospital page: http://localhost:" + port + "/hospital");
        System.out.println("  Patient page:  http://localhost:" + port + "/user");
    }

    // =====================================================================
    // Routing
    // =====================================================================

    void handle(HttpExchange ex) throws IOException {
        try {
            synchronized (this) {          // Scheduler isn't thread-safe: one request at a time
                route(ex);
            }
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, page("Error", "<p>Something went wrong: " + esc(e.toString()) + "</p><p><a href=\"/\">Home</a></p>"));
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        boolean post = ex.getRequestMethod().equalsIgnoreCase("POST");
        Map<String, String> query = parseForm(ex.getRequestURI().getRawQuery());
        Map<String, String> form = post
                ? parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)) : Map.of();
        int now = now();
        scheduler.tick(now);               // expire waitlist offers nobody answered in time

        switch (path) {
            case "/" -> send(ex, 200, homePage());
            case "/hospital" -> send(ex, 200, hospitalPage(query, now));
            case "/hospital/hours" -> {
                if (post) saveHours(form, now);
                redirect(ex, "/hospital");
            }
            case "/user" -> send(ex, 200, userPage(session(ex), query, now));
            default -> {
                if (!path.startsWith("/user/")) {
                    send(ex, 404, page("Not found", "<p>Page not found. <a href=\"/\">Home</a></p>"));
                    return;
                }
                Session s = session(ex);
                if (post) {
                    switch (path) {
                        case "/user/enter" -> enter(ex, s, form, now);
                        case "/user/logout" -> logout(s);
                        case "/user/book" -> book(s, form, now);
                        case "/user/waitlist" -> joinWaitlist(s, now);
                        case "/user/clear" -> { s.pending = null; s.pendingProblem = null; }
                        case "/user/cancel" -> cancelAppointment(s, now);
                        case "/user/leave" -> leaveWaitlist(s, now);
                        case "/user/accept" -> acceptOffer(s, now);
                        case "/user/decline" -> declineOffer(s, now);
                        default -> { }
                    }
                }
                redirect(ex, "/user");     // post-redirect-get: refreshing never resubmits a form
            }
        }
    }

    // =====================================================================
    // Hospital actions
    // =====================================================================

    private void saveHours(Map<String, String> form, int now) {
        Map<Integer, Scheduler.Window> next = new TreeMap<>();
        List<String> errors = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            if (!form.containsKey("open_" + d)) continue;          // unchecked = closed that day
            try {
                int from = minuteOfDay(LocalTime.parse(form.getOrDefault("from_" + d, "")));
                int to = minuteOfDay(LocalTime.parse(form.getOrDefault("to_" + d, "")));
                if (from % BLOCK != 0 || to % BLOCK != 0)
                    errors.add(WEEKDAYS[d] + ": times must be on 15-minute marks.");
                else if (to <= from)
                    errors.add(WEEKDAYS[d] + ": closing time must be after opening time.");
                else next.put(d, new Scheduler.Window(from, to));
            } catch (DateTimeParseException e) {
                errors.add(WEEKDAYS[d] + ": enter both an opening and a closing time.");
            }
        }
        if (!errors.isEmpty()) {
            hospitalFlash = "Hours NOT saved. " + String.join(" ", errors);
            return;
        }
        hours.clear();
        hours.putAll(next);
        Map<Integer, List<Scheduler.Window>> weekly = new HashMap<>();
        next.forEach((day, w) -> weekly.put(day, List.of(w)));
        scheduler.setHours(PROVIDER, weekly);
        hoursSet = true;

        int outside = 0;
        for (Scheduler.Appointment a : scheduler.getAppointments())
            if (a.isActive() && a.end > now && !scheduler.withinHours(PROVIDER, a.start, a.end - a.start)) outside++;
        hospitalFlash = "Hours saved. " + (next.isEmpty() ? "Every day is closed, so nothing can be booked." : "Patients can book.")
                + (outside > 0 ? " Note: " + outside + " existing appointment(s) are now outside opening hours; they were kept." : "");
    }

    // =====================================================================
    // Patient actions
    // =====================================================================

    /** One form for both: a new username creates an account; an existing one logs in only with its password. */
    private void enter(HttpExchange ex, Session old, Map<String, String> form, int now) {
        String username = form.getOrDefault("username", "").trim();
        String password = form.getOrDefault("password", "");
        if (!username.matches("[A-Za-z0-9_.-]{1,30}")) {
            old.flash = "Username must be 1-30 characters: letters, numbers, dot, dash or underscore.";
            return;
        }
        if (password.isEmpty()) {
            old.flash = "Please enter a password.";
            return;
        }
        String key = username.toLowerCase(Locale.ROOT);    // "Sam" and "sam" are the same user
        Account acc = accounts.get(key);
        String welcome;
        if (acc == null) {
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            accounts.put(key, new Account(username, salt, hashPassword(password, salt), now));
            welcome = "Account created. Welcome, " + username + ".";
        } else if (MessageDigest.isEqual(acc.passwordHash, hashPassword(password, acc.salt))) {
            welcome = "Welcome back, " + acc.username + ".";
        } else {
            old.flash = "Username taken.";
            return;
        }
        sessions.values().remove(old);     // new session id on login (prevents session fixation)
        Session s = newSession(ex);
        s.accountKey = key;
        s.flash = welcome;
    }

    private void logout(Session s) {
        s.accountKey = null;
        s.pending = null;
        s.draft = null;
        s.flash = "Logged out. Enter a username and password to continue.";
    }

    private void book(Session s, Map<String, String> form, int now) {
        Account acc = account(s);
        if (acc == null || !hoursSet) return;
        if (activeRequest(acc, now) != null) {
            s.flash = "You already have an appointment or a waitlist spot. Delete it first to book a different time.";
            return;
        }
        List<String> error = new ArrayList<>();
        Booking b = parseBooking(form, error);
        s.draft = new HashMap<>(form);
        if (b == null) {
            s.flash = error.get(0);
            return;
        }
        String problem = problemWith(b.start(), b.duration(), now);
        if (problem != null) {             // show the "waitlist or pick another time" choice
            s.pending = new HashMap<>(form);
            s.pendingProblem = problem;
            return;
        }
        Scheduler.Request r = newRequest(acc, b, now);
        Scheduler.Appointment a = scheduler.bookAt(r, PROVIDER, b.start(), now);
        acc.currentRequestId = r.id;
        s.pending = null;
        s.draft = null;
        s.flash = "Booked: " + when(a.start) + " to " + hhmm(a.end % DAY) + ".";
    }

    private void joinWaitlist(Session s, int now) {
        Account acc = account(s);
        if (acc == null || s.pending == null) return;
        if (activeRequest(acc, now) != null) return;
        List<String> error = new ArrayList<>();
        Booking b = parseBooking(s.pending, error);
        if (b == null) {
            s.flash = error.get(0);
            s.pending = null;
            return;
        }
        Scheduler.Request r = newRequest(acc, b, now);
        if (!scheduler.joinWaitlist(r, now)) {
            s.flash = "The hospital is never open long enough for a " + b.duration()
                    + "-minute visit, so you can't join the waitlist for it.";
            return;
        }
        acc.currentRequestId = r.id;
        s.pending = null;
        s.draft = null;
        s.flash = "You joined the waitlist. You are #" + scheduler.waitlistPosition(r.id) + " in line.";
    }

    private void cancelAppointment(Session s, int now) {
        Account acc = account(s);
        Scheduler.Request r = acc == null ? null : activeRequest(acc, now);
        if (r == null) return;
        Scheduler.Appointment a = scheduler.activeAppointmentFor(r.id);
        if (a == null || a.status != Scheduler.AppointmentStatus.BOOKED) return;
        scheduler.cancel(a.id, now);       // the freed time is offered to the waitlist
        acc.currentRequestId = null;
        s.flash = "Your appointment on " + when(a.start) + " was deleted.";
    }

    private void leaveWaitlist(Session s, int now) {
        Account acc = account(s);
        Scheduler.Request r = acc == null ? null : activeRequest(acc, now);
        if (r == null) return;
        if (r.status == Scheduler.RequestStatus.OFFERED) {
            Scheduler.Appointment hold = scheduler.activeAppointmentFor(r.id);
            if (hold != null) scheduler.cancel(hold.id, now);  // also passes the offer to the next person
        } else if (r.status == Scheduler.RequestStatus.WAITING) {
            scheduler.removeFromWaitlist(r.id);
        } else {
            return;
        }
        acc.currentRequestId = null;
        s.flash = "You left the waitlist.";
    }

    private void acceptOffer(Session s, int now) {
        Account acc = account(s);
        Scheduler.Request r = acc == null ? null : activeRequest(acc, now);
        Scheduler.Appointment a = r == null ? null : scheduler.activeAppointmentFor(r.id);
        if (a != null && scheduler.confirm(a.id, now))
            s.flash = "Confirmed: " + when(a.start) + " to " + hhmm(a.end % DAY) + ".";
        else
            s.flash = "That offer is no longer available.";
    }

    private void declineOffer(Session s, int now) {
        Account acc = account(s);
        Scheduler.Request r = acc == null ? null : activeRequest(acc, now);
        Scheduler.Appointment a = r == null ? null : scheduler.activeAppointmentFor(r.id);
        if (a != null && scheduler.declineHold(a.id, now))
            s.flash = "You declined that time. You're back in line at #" + scheduler.waitlistPosition(r.id) + ".";
    }

    // =====================================================================
    // Booking helpers
    // =====================================================================

    /** Parses the booking form. On a problem, adds a message to error and returns null. */
    private Booking parseBooking(Map<String, String> form, List<String> error) {
        LocalDate date;
        int timeOfDay;
        try {
            date = LocalDate.parse(form.getOrDefault("date", ""));
        } catch (DateTimeParseException e) {
            error.add("Please choose a date.");
            return null;
        }
        try {
            timeOfDay = minuteOfDay(LocalTime.parse(form.getOrDefault("time", "")));
        } catch (DateTimeParseException e) {
            error.add("Please enter a start time, like 09:30.");
            return null;
        }
        if (timeOfDay % BLOCK != 0) {
            error.add("Start times must be on a 15-minute mark (:00, :15, :30 or :45).");
            return null;
        }
        int duration = parseInt(form.get("duration"), -1);
        if (Arrays.stream(DURATIONS).noneMatch(d -> d == duration)) {
            error.add("Please choose a duration.");
            return null;
        }
        long daysAhead = ChronoUnit.DAYS.between(LocalDate.now(clock), date);
        if (daysAhead < 0 || daysAhead >= CALENDAR_DAYS) {
            error.add("Please choose a date within the next " + CALENDAR_DAYS + " days.");
            return null;
        }
        int severity = parseInt(form.get("severity"), 1);
        if (severity < 1 || severity > 5) severity = 1;
        String reason = form.getOrDefault("reason", "").trim();
        if (reason.length() > 500) reason = reason.substring(0, 500);
        return new Booking(minuteOf(date, timeOfDay), duration, severity, reason);
    }

    /** Why this exact time can't be booked, or null if it's open. */
    private String problemWith(int start, int duration, int now) {
        if (start < now) return "That time has already passed.";
        if (!scheduler.withinHours(PROVIDER, start, duration))
            return "The hospital isn't open for that whole " + duration + "-minute visit.";
        if (!scheduler.blocksFree(PROVIDER, start, start + duration))
            return "Someone is already booked during that time.";
        return null;
    }

    private Scheduler.Request newRequest(Account acc, Booking b, int now) {
        Scheduler.Request r = new Scheduler.Request(nextRequestId++, acc.username, SPECIALTY,
                b.duration(), b.severity(), now, null, null);
        r.visitReason = b.reason();
        r.requestedStart = b.start();
        return r;
    }

    /** The patient's current appointment or waitlist spot; null once it's cancelled or over. */
    private Scheduler.Request activeRequest(Account acc, int now) {
        if (acc.currentRequestId == null) return null;
        Scheduler.Request r = scheduler.getRequest(acc.currentRequestId);
        boolean active = false;
        if (r != null) {
            switch (r.status) {
                case WAITING, OFFERED -> active = true;
                case BOOKED -> {
                    Scheduler.Appointment a = scheduler.activeAppointmentFor(r.id);
                    active = a != null && a.end > now;
                }
                default -> { }
            }
        }
        if (!active) acc.currentRequestId = null;
        return active ? r : null;
    }

    // =====================================================================
    // Pages
    // =====================================================================

    private String homePage() {
        return page("Clinic Scheduler", """
                <h1>Clinic Scheduler</h1>
                <ul>
                <li><a href="/hospital">Hospital page</a> - set opening hours, view the calendar and the waitlist queue</li>
                <li><a href="/user">Patient page</a> - create an account and book an appointment</li>
                </ul>
                """);
    }

    private String hospitalPage(Map<String, String> query, int now) {
        StringBuilder h = new StringBuilder();
        h.append("<p><a href=\"/\">Home</a> | <a href=\"/user\">Patient page</a></p>");
        h.append("<h1>Hospital</h1>");
        h.append("<p>Current time: ").append(esc(when(now))).append("</p>");
        if (hospitalFlash != null) {
            h.append("<p class=\"msg\">").append(esc(hospitalFlash)).append("</p>");
            hospitalFlash = null;
        }

        // ----- opening hours -----
        h.append("<h2>Opening hours</h2>");
        if (!hoursSet) h.append("<p>Patients can't book until you save opening hours.</p>");
        h.append("<form method=\"post\" action=\"/hospital/hours\"><table>")
         .append("<tr><th>Day</th><th>Open</th><th>Opens at</th><th>Closes at</th></tr>");
        for (int d = 0; d < 7; d++) {
            Scheduler.Window w = hours.get(d);
            boolean open = hoursSet ? w != null : d < 5;     // suggest Mon-Fri before the first save
            String from = w != null ? hhmm(w.start()) : "08:00";
            String to = w != null ? hhmm(w.end()) : "17:00";
            h.append("<tr><td>").append(WEEKDAYS[d]).append("</td>")
             .append("<td><input type=\"checkbox\" name=\"open_").append(d).append("\"").append(open ? " checked" : "").append("></td>")
             .append("<td><input type=\"time\" step=\"900\" name=\"from_").append(d).append("\" value=\"").append(from).append("\"></td>")
             .append("<td><input type=\"time\" step=\"900\" name=\"to_").append(d).append("\" value=\"").append(to).append("\"></td></tr>");
        }
        h.append("</table><button type=\"submit\">Save hours</button></form>");

        // ----- appointment details (after clicking one in the calendar) -----
        Scheduler.Appointment a = query.containsKey("appt") ? scheduler.getAppointment(parseInt(query.get("appt"), -1)) : null;
        if (a != null) {
            Scheduler.Request r = a.request;
            Account acc = accounts.get(r.patient.toLowerCase(Locale.ROOT));
            String status = switch (a.status) {
                case BOOKED -> "Booked";
                case HELD -> "Offered from the waitlist, waiting for the patient to accept (expires " + when(a.holdExpires) + ")";
                default -> a.status.toString().charAt(0) + a.status.toString().substring(1).toLowerCase(Locale.ROOT);
            };
            h.append("<h2 id=\"details\">Appointment details</h2><table>")
             .append(row("Patient", esc(r.patient)))
             .append(row("Status", esc(status)))
             .append(row("Date and time", esc(when(a.start) + " to " + hhmm(a.end % DAY))))
             .append(row("Duration", (a.end - a.start) + " minutes"))
             .append(row("Severity", esc(severity(r.urgency))))
             .append(row("Reason for visit", r.visitReason.isEmpty() ? "(none given)" : multiline(r.visitReason)))
             .append(row("Requested on", esc(when(r.createdAt))));
            if (r.requestedStart != null && r.requestedStart != a.start)
                h.append(row("Originally asked for", esc(when(r.requestedStart))));
            if (acc != null) h.append(row("Account created", esc(when(acc.createdAt))));
            h.append(row("How it was scheduled", esc(a.reason)))
             .append("</table><p><a href=\"/hospital\">Close details</a></p>");
        }

        // ----- calendar -----
        h.append("<h2>Calendar</h2>");
        h.append("<p>Click an appointment to see the patient's details.</p>");
        h.append(calendar(null, now));

        // ----- queue -----
        h.append("<h2>Waitlist queue</h2>");
        h.append("<p>Ordered by severity, then by how long each patient has waited. "
                + "A cancellation is offered to the first patient whose visit fits in the freed time.</p>");
        List<Scheduler.Request> queue = scheduler.waitlistInOrder();
        if (queue.isEmpty()) {
            h.append("<p>No one is waiting.</p>");
        } else {
            h.append("<table><tr><th>#</th><th>Patient</th><th>Severity</th><th>Duration</th>"
                    + "<th>Waiting since</th><th>Asked for</th><th>Reason for visit</th></tr>");
            for (int i = 0; i < queue.size(); i++) {
                Scheduler.Request r = queue.get(i);
                h.append("<tr><td>").append(i + 1).append("</td><td>").append(esc(r.patient))
                 .append("</td><td>").append(esc(severity(r.urgency)))
                 .append("</td><td>").append(r.duration).append(" min</td><td>").append(esc(when(r.createdAt)))
                 .append("</td><td>").append(r.requestedStart == null ? "" : esc(when(r.requestedStart)))
                 .append("</td><td>").append(multiline(r.visitReason)).append("</td></tr>");
            }
            h.append("</table>");
        }
        List<Scheduler.Appointment> offers = new ArrayList<>();
        for (Scheduler.Appointment x : scheduler.getAppointments())
            if (x.status == Scheduler.AppointmentStatus.HELD) offers.add(x);
        if (!offers.isEmpty()) {
            h.append("<h3>Offers waiting for a reply</h3><table><tr><th>Patient</th><th>Offered time</th><th>Expires</th></tr>");
            for (Scheduler.Appointment x : offers)
                h.append("<tr><td>").append(esc(x.request.patient)).append("</td><td>")
                 .append(esc(when(x.start) + " to " + hhmm(x.end % DAY))).append("</td><td>")
                 .append(esc(when(x.holdExpires))).append("</td></tr>");
            h.append("</table>");
        }
        return page("Hospital", h.toString());
    }

    private String userPage(Session s, Map<String, String> query, int now) {
        StringBuilder h = new StringBuilder();
        h.append("<p><a href=\"/\">Home</a></p>");
        h.append("<h1>Patient</h1>");
        if (s.flash != null) {
            h.append("<p class=\"msg\">").append(esc(s.flash)).append("</p>");
            s.flash = null;
        }
        if (!hoursSet) {
            h.append("<p>The hospital hasn't set its opening hours yet, so booking isn't available. Please check back later.</p>");
            return page("Patient", h.toString());
        }

        Account acc = account(s);
        if (acc == null) {
            h.append("<h2>New user / log in</h2>")
             .append("<p>Enter a username and password. A new username creates an account. ")
             .append("An existing username with its password logs you in.</p>")
             .append("<form method=\"post\" action=\"/user/enter\"><table class=\"form\">")
             .append("<tr><td>Username</td><td><input name=\"username\" maxlength=\"30\" autocomplete=\"username\"></td></tr>")
             .append("<tr><td>Password</td><td><input type=\"password\" name=\"password\" autocomplete=\"current-password\"></td></tr>")
             .append("</table><button type=\"submit\">New user / Log in</button></form>");
            return page("Patient", h.toString());
        }

        h.append("<p>Logged in as <b>").append(esc(acc.username)).append("</b> ")
         .append(button("/user/logout", "New user / Log out")).append("</p>");

        Scheduler.Request r = activeRequest(acc, now);
        if (r != null) h.append(statusBox(r, now));
        else if (s.pending != null) h.append(notAvailableBox(s, now));
        else h.append(bookingForm(s, query));

        h.append("<h2>Calendar</h2>");
        if (r == null && s.pending == null) h.append("<p>Click an \"open\" time to fill in the form above.</p>");
        h.append(calendar(acc, now));
        return page("Patient", h.toString());
    }

    private String statusBox(Scheduler.Request r, int now) {
        Scheduler.Appointment a = scheduler.activeAppointmentFor(r.id);
        StringBuilder b = new StringBuilder("<div class=\"box\">");
        if (r.status == Scheduler.RequestStatus.BOOKED && a != null) {
            b.append("<h2>Your appointment</h2><table>")
             .append(row("When", esc(when(a.start) + " to " + hhmm(a.end % DAY))))
             .append(row("Duration", (a.end - a.start) + " minutes"))
             .append(visitRows(r)).append("</table>")
             .append(button("/user/cancel", "Delete appointment"));
        } else if (r.status == Scheduler.RequestStatus.OFFERED && a != null) {
            b.append("<h2>An opening came up for you</h2>")
             .append("<p><b>").append(esc(when(a.start) + " to " + hhmm(a.end % DAY))).append("</b></p>")
             .append("<p>Reply by ").append(esc(when(a.holdExpires)))
             .append(" or it goes to the next person in line. If you decline, you keep your place in line.</p>")
             .append(button("/user/accept", "Accept this time")).append(" ")
             .append(button("/user/decline", "Decline, stay in line")).append(" ")
             .append(button("/user/leave", "Leave the waitlist"));
        } else {
            int position = scheduler.waitlistPosition(r.id), size = scheduler.waitlistInOrder().size();
            b.append("<h2>You are on the waitlist</h2>")
             .append("<p>Position in queue: <b>#").append(position).append("</b> of ").append(size).append("</p>")
             .append("<p>When a cancellation frees at least ").append(r.duration)
             .append(" minutes, it's offered to the first person in line it fits. If it's offered to you, ")
             .append("it will appear here and you'll have 2 hours to accept.</p><table>")
             .append(row("Visit length", r.duration + " minutes"));
            if (r.requestedStart != null) b.append(row("You asked for", esc(when(r.requestedStart))));
            b.append(visitRows(r)).append("</table>")
             .append(button("/user/leave", "Leave the waitlist"));
        }
        return b.append("</div>").toString();
    }

    private String visitRows(Scheduler.Request r) {
        return row("Severity", esc(severity(r.urgency)))
             + row("Reason for visit", r.visitReason.isEmpty() ? "(none given)" : multiline(r.visitReason));
    }

    private String notAvailableBox(Session s, int now) {
        Map<String, String> p = s.pending;
        List<String> ignored = new ArrayList<>();
        Booking b = parseBooking(p, ignored);
        StringBuilder h = new StringBuilder("<div class=\"box\">");
        h.append("<h2>That time is not available</h2><p>").append(esc(s.pendingProblem)).append("</p>");

        h.append("<h3>Option 1: join the waitlist</h3>")
         .append("<p>People waiting now: ").append(scheduler.waitlistInOrder().size())
         .append(". You'll be offered the next cancellation that fits a ").append(p.get("duration"))
         .append("-minute visit.</p>")
         .append(button("/user/waitlist", "Put me on the waitlist"));

        h.append("<h3>Option 2: pick an open time instead</h3>");
        List<Integer> open = b == null ? List.of()
                : scheduler.openStarts(PROVIDER, b.duration(), now, todayStart() + CALENDAR_DAYS * DAY, now);
        if (open.isEmpty()) {
            h.append("<p>There are no open times for a visit this long in the next ").append(CALENDAR_DAYS).append(" days.</p>");
        } else {
            List<Integer> nearest = new ArrayList<>(open);   // the 8 open times closest to what they asked for
            nearest.sort(Comparator.comparingInt(t -> Math.abs(t - b.start())));
            nearest = new ArrayList<>(nearest.subList(0, Math.min(8, nearest.size())));
            Collections.sort(nearest);
            h.append("<p>Open times closest to what you asked for:</p>");
            for (int t : nearest) {
                h.append("<form method=\"post\" action=\"/user/book\" class=\"inline\">")
                 .append(hidden("date", dateOf(t).toString()))
                 .append(hidden("time", hhmm(t % DAY)))
                 .append(hidden("duration", p.get("duration")))
                 .append(hidden("severity", p.get("severity")))
                 .append(hidden("reason", p.get("reason")))
                 .append("<button type=\"submit\">Book ").append(esc(when(t))).append("</button></form> ");
            }
        }
        h.append("<p>").append(button("/user/clear", "Go back to the form")).append("</p>");
        return h.append("</div>").toString();
    }

    private String bookingForm(Session s, Map<String, String> query) {
        Map<String, String> v = s.draft != null ? s.draft : Map.of();
        String date = v.getOrDefault("date", LocalDate.now(clock).toString());
        String time = v.getOrDefault("time", "");
        int pick = parseInt(query.get("pick"), -1);          // clicked an open cell in the calendar
        if (pick >= 0) {
            date = dateOf(pick).toString();
            time = hhmm(pick % DAY);
        }
        int duration = parseInt(v.get("duration"), 30);
        int severity = parseInt(v.get("severity"), 1);
        String reason = v.getOrDefault("reason", "");

        StringBuilder h = new StringBuilder("<div class=\"box\" id=\"book\"><h2>Book an appointment</h2>");
        h.append("<form method=\"post\" action=\"/user/book\"><table class=\"form\">")
         .append("<tr><td>Date</td><td><input type=\"date\" name=\"date\" value=\"").append(esc(date))
         .append("\" min=\"").append(LocalDate.now(clock)).append("\" max=\"")
         .append(LocalDate.now(clock).plusDays(CALENDAR_DAYS - 1)).append("\"></td></tr>")
         .append("<tr><td>Start time</td><td><input type=\"time\" step=\"900\" name=\"time\" value=\"").append(esc(time))
         .append("\"> (on a 15-minute mark)</td></tr>")
         .append("<tr><td>Duration</td><td><select name=\"duration\">");
        for (int d : DURATIONS)
            h.append("<option value=\"").append(d).append("\"").append(d == duration ? " selected" : "")
             .append(">").append(d).append(" minutes</option>");
        h.append("</select></td></tr><tr><td>Severity</td><td>");
        for (int u = 1; u <= 5; u++)
            h.append("<label><input type=\"radio\" name=\"severity\" value=\"").append(u).append("\"")
             .append(u == severity ? " checked" : "").append("> ").append(severity(u)).append("</label><br>");
        h.append("</td></tr><tr><td>Reason for visit</td><td><textarea name=\"reason\" maxlength=\"500\">")
         .append(esc(reason)).append("</textarea></td></tr></table>")
         .append("<button type=\"submit\">Submit</button></form></div>");
        return h.toString();
    }

    /**
     * Two-week calendar, one table per week, one row per 15 minutes.
     * viewer == null: hospital view (names, clickable). Otherwise: patient view (open/booked/yours).
     */
    private String calendar(Account viewer, int now) {
        int today = todayStart(), horizon = today + CALENDAR_DAYS * DAY;
        int first = Integer.MAX_VALUE, last = Integer.MIN_VALUE;
        for (Scheduler.Window w : hours.values()) {
            first = Math.min(first, w.start());
            last = Math.max(last, w.end());
        }
        for (Scheduler.Appointment a : scheduler.getAppointments()) {   // show bookings outside current hours too
            if (!a.isActive() || a.end <= today || a.start >= horizon) continue;
            int dayStart = (a.start / DAY) * DAY;
            first = Math.min(first, a.start - dayStart);
            last = Math.max(last, a.end - dayStart);
        }
        if (first == Integer.MAX_VALUE) return "<p>The hospital is closed every day.</p>";

        Integer mine = viewer == null ? null : viewer.currentRequestId;
        boolean canPick = viewer != null && mine == null;
        StringBuilder c = new StringBuilder();
        for (int week = 0; week < CALENDAR_DAYS / 7; week++) {
            c.append("<div class=\"scroll\"><table class=\"cal\"><tr><th>Time</th>");
            for (int d = 0; d < 7; d++)
                c.append("<th>").append(dateOf(today + (week * 7 + d) * DAY).format(SHORT_DATE)).append("</th>");
            c.append("</tr>");
            for (int t = first; t < last; t += BLOCK) {
                c.append("<tr><th>").append(hhmm(t)).append("</th>");
                for (int d = 0; d < 7; d++)
                    c.append(cell(today + (week * 7 + d) * DAY + t, viewer == null, mine, canPick, now));
                c.append("</tr>");
            }
            c.append("</table></div>");
        }
        return c.toString();
    }

    private String cell(int minute, boolean hospital, Integer myRequest, boolean canPick, int now) {
        Integer id = scheduler.appointmentAt(PROVIDER, minute);
        if (id != null) {
            Scheduler.Appointment a = scheduler.getAppointment(id);
            boolean firstBlock = a.start == minute, held = a.status == Scheduler.AppointmentStatus.HELD;
            if (hospital) {
                String label = firstBlock ? (held ? "offered: " : "") + esc(a.request.patient) : "|";
                return "<td class=\"booked\"><a href=\"/hospital?appt=" + a.id + "#details\">" + label + "</a></td>";
            }
            if (myRequest != null && a.request.id == myRequest)
                return "<td class=\"mine\">" + (firstBlock ? (held ? "OFFERED TO YOU" : "YOUR VISIT") : "|") + "</td>";
            return "<td class=\"booked\">booked</td>";
        }
        if (!scheduler.withinHours(PROVIDER, minute, BLOCK)) return "<td class=\"closed\">closed</td>";
        if (minute < now) return "<td class=\"past\">past</td>";
        if (!hospital && canPick) return "<td class=\"open\"><a href=\"/user?pick=" + minute + "#book\">open</a></td>";
        return "<td class=\"open\">open</td>";
    }

    // =====================================================================
    // Time helpers: scheduler minutes <-> local dates and times
    // =====================================================================

    private int now() {
        LocalDateTime t = LocalDateTime.now(clock);
        return minuteOf(t.toLocalDate(), t.getHour() * 60 + t.getMinute());
    }

    private int todayStart() { return minuteOf(LocalDate.now(clock), 0); }

    private int minuteOf(LocalDate date, int minuteOfDay) {
        return (int) ChronoUnit.DAYS.between(baseMonday, date) * DAY + minuteOfDay;
    }

    private LocalDate dateOf(int minute) { return baseMonday.plusDays(minute / DAY); }

    /** e.g. "Tue Oct 20, 09:30" */
    private String when(int minute) { return dateOf(minute).format(LONG_DATE) + ", " + hhmm(minute % DAY); }

    static String hhmm(int minuteOfDay) { return String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60); }

    static int minuteOfDay(LocalTime t) { return t.getHour() * 60 + t.getMinute(); }

    static String severity(int u) { return u + " - " + SEVERITY[u]; }

    // =====================================================================
    // Accounts and sessions
    // =====================================================================

    /** PBKDF2 with a random per-user salt: slow on purpose, so stolen hashes are hard to crack. */
    static byte[] hashPassword(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120_000, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private Account account(Session s) { return s.accountKey == null ? null : accounts.get(s.accountKey); }

    private Session session(HttpExchange ex) {
        String sid = cookie(ex, "sid");
        Session s = sid == null ? null : sessions.get(sid);
        return s != null ? s : newSession(ex);
    }

    private Session newSession(HttpExchange ex) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String sid = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session s = new Session();
        sessions.put(sid, s);
        ex.getResponseHeaders().set("Set-Cookie", "sid=" + sid + "; Path=/; HttpOnly; SameSite=Lax");
        return s;
    }

    private static String cookie(HttpExchange ex, String name) {
        String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    // =====================================================================
    // HTTP and HTML helpers
    // =====================================================================

    static Map<String, String> parseForm(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(key, value);
        }
        return out;
    }

    private static void send(HttpExchange ex, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(303, -1);
        ex.close();
    }

    /** Escapes text before putting it in HTML, so a patient can't inject markup or scripts. */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(ch);
            }
        }
        return b.toString();
    }

    private static String multiline(String s) { return esc(s).replace("\n", "<br>"); }

    private static String row(String label, String htmlValue) {
        return "<tr><th>" + label + "</th><td>" + htmlValue + "</td></tr>";
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + name + "\" value=\"" + esc(value) + "\">";
    }

    private static String button(String action, String label) {
        return "<form method=\"post\" action=\"" + action + "\" class=\"inline\"><button type=\"submit\">"
                + esc(label) + "</button></form>";
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final String STYLE = """
            body { font-family: Arial, Helvetica, sans-serif; background: #e0e0e0; color: #000; margin: 16px; }
            a { color: #000; }
            input, select, textarea, button { accent-color: #000; }
            table { border-collapse: collapse; margin: 8px 0; background: #f0f0f0; }
            th, td { border: 1px solid #000; padding: 3px 6px; font-size: 13px; text-align: left; vertical-align: top; }
            th { background: #c8c8c8; }
            table.form td { border: none; background: none; }
            table.form { background: none; }
            table.cal td { width: 90px; }
            td.closed { background: #a0a0a0; color: #555; }
            td.past { background: #c0c0c0; color: #555; }
            td.open { background: #fff; }
            td.booked { background: #d4d4d4; }
            td.mine { background: #000; color: #fff; }
            .msg { border: 1px solid #000; background: #fff; padding: 6px; }
            .box { border: 1px solid #000; background: #ececec; padding: 8px 12px; margin: 8px 0; }
            .inline { display: inline; }
            .scroll { overflow-x: auto; }
            textarea { width: 300px; height: 60px; }
            """;

    private static String page(String title, String body) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + esc(title) + "</title><style>" + STYLE + "</style></head><body>"
                + body + "</body></html>";
    }
}
