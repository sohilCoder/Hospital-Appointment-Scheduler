import java.util.*;

/**
 * Clinic appointment scheduler with an urgency-and-aging priority waitlist.
 *
 * Time is an int number of minutes since the start (minute 0 = Monday 00:00).
 * Each provider's time is split into 15-minute blocks. An appointment claims every
 * block it covers, and claim() is the only place blocks are taken, so two active
 * appointments can never overlap.
 */
public class Scheduler {

    // ----- constants -----
    public static final int BLOCK = 15;                 // minutes per block
    public static final int DAY = 24 * 60;              // minutes per day
    /** With agingRate = 1, one urgency level is worth a week of waiting. */
    public static final long URGENCY_WEIGHT = 7L * DAY;
    public static final int HOLD_MINUTES = 120;         // how long a waitlist offer stays open
    /** Longest acceptable wait in days, indexed by urgency 1..5 (index 0 unused). */
    public static final int[] MAX_WAIT_DAYS = {0, 30, 21, 14, 7, 2};
    /**
     * Late-afternoon time (3 PM onward) is reserved for short-notice urgent care. Urgent
     * patients (urgency >= 4) may book it at most URGENT_LOOKAHEAD ahead, so it can't be used
     * up days in advance. Within RESERVE_RELEASE, anyone may book it so it isn't wasted.
     */
    public static final int RESERVED_FROM = 15 * 60;
    public static final int URGENT_LEVEL = 4;
    public static final int URGENT_LOOKAHEAD = 2 * DAY;
    public static final int RESERVE_RELEASE = DAY;
    private static final int MORNING_END = 12 * 60;     // noon
    private static final double PREFERENCE_BONUS = 50;  // score for matching time-of-day preference

    // ----- small nested types -----
    public enum RequestStatus { WAITING, OFFERED, BOOKED, REMOVED, UNSCHEDULABLE }
    public enum AppointmentStatus { BOOKED, HELD, CANCELLED, EXPIRED, DECLINED }

    /** A stretch of working time within a day, in minutes since midnight (e.g. 480-720 = 8:00-12:00). */
    public record Window(int start, int end) {
        public Window {
            if (start % BLOCK != 0 || end % BLOCK != 0 || end <= start)
                throw new IllegalArgumentException("Window must be aligned to 15 minutes: " + start + "-" + end);
        }
    }

    /** hours maps weekday (0 = Monday ... 6 = Sunday) to that day's working windows. */
    public record Provider(String name, String specialty, Map<Integer, List<Window>> hours) {}

    /** A candidate start time with a specific provider. */
    public record Slot(String provider, int start) {}

    /** Heap entry: a priority key that never changes, plus a first-come tiebreaker. */
    private record WaitEntry(long key, long seq, Request request) {}

    public static class Request {
        public final int id;
        public final String patient;
        public final String specialty;
        public final int duration;              // minutes, multiple of 15
        public final int urgency;               // 1 (routine) to 5 (urgent)
        public final int createdAt;             // minute timestamp
        public final Set<Integer> allowedDays;  // HARD constraint; null = any day
        public final Boolean prefersMorning;    // SOFT preference; null = no preference
        public RequestStatus status = RequestStatus.WAITING;
        public String visitReason = "";         // patient's description of why they're coming in
        public Integer requestedStart;          // the exact time the patient asked for, if any
        long queueSeq = -1;                     // place in line, assigned on first waitlist entry

        public Request(int id, String patient, String specialty, int duration, int urgency,
                       int createdAt, Set<Integer> allowedDays, Boolean prefersMorning) {
            if (duration <= 0 || duration % BLOCK != 0)
                throw new IllegalArgumentException("Duration must be a positive multiple of 15");
            if (urgency < 1 || urgency > 5)
                throw new IllegalArgumentException("Urgency must be 1-5");
            this.id = id; this.patient = patient; this.specialty = specialty;
            this.duration = duration; this.urgency = urgency; this.createdAt = createdAt;
            this.allowedDays = allowedDays; this.prefersMorning = prefersMorning;
        }

        @Override public String toString() {
            return "Request#" + id + "(" + patient + ", " + specialty + ", " + duration + "min, urgency " + urgency + ")";
        }
    }

    public static class Appointment {
        public final int id;
        public final Request request;
        public final String provider;
        public final int start, end;
        public AppointmentStatus status;
        public Integer holdExpires;  // only set while HELD
        public String reason;        // why this slot went to this patient

        Appointment(int id, Request request, String provider, int start, int end) {
            this.id = id; this.request = request; this.provider = provider;
            this.start = start; this.end = end;
        }

        public boolean isActive() {
            return status == AppointmentStatus.BOOKED || status == AppointmentStatus.HELD;
        }

        @Override public String toString() {
            return "Appt#" + id + " " + request.patient + " with " + provider + " at " + format(start)
                    + " (" + (end - start) + " min) [" + status + "] " + reason;
        }
    }

    // ----- state -----
    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private final Map<String, Map<Integer, Integer>> booked = new HashMap<>(); // provider -> (block -> appointment id)
    private final Map<Integer, Appointment> appointments = new LinkedHashMap<>();
    private final Map<Integer, Request> requests = new HashMap<>();
    private final PriorityQueue<WaitEntry> waitlist = new PriorityQueue<>(
            Comparator.comparingLong(WaitEntry::key).thenComparingLong(WaitEntry::seq));
    private final long agingRate;     // priority points per minute waited (0 = no aging)
    private boolean urgentReserve = true;  // keep late-afternoon time for urgent patients
    private long nextSeq = 0;
    private int nextAppointmentId = 1;

    // ----- counters for reporting -----
    private int cancelledFutureMinutes = 0;  // provider time freed by cancellations
    private int refilledMinutes = 0;         // freed time that a waitlisted patient confirmed

    public Scheduler(List<Provider> providerList, long agingRate) {
        this.agingRate = agingRate;
        for (Provider p : providerList) {
            providers.put(p.name(), p);
            booked.put(p.name(), new HashMap<>());
        }
    }

    public Scheduler(List<Provider> providerList) {
        this(providerList, 1);
    }

    // =====================================================================
    // Public actions
    // =====================================================================

    /** Books the best slot for a new request, or puts it on the waitlist. Returns null if waitlisted. */
    public Appointment request(Request req, int now) {
        requests.put(req.id, req);
        if (!everPossible(req)) {              // e.g. cardiology on Tuesdays only, but cardiology is Mon/Wed/Fri
            req.status = RequestStatus.UNSCHEDULABLE;
            return null;
        }
        Appointment appt = tryBook(req, now);
        if (appt == null) {
            req.status = RequestStatus.WAITING;
            pushWaitlist(req);
        }
        return appt;
    }

    /** Patient cancels. Frees the time and offers it to the waitlist. */
    public boolean cancel(int appointmentId, int now) {
        Appointment a = appointments.get(appointmentId);
        if (a == null || !a.isActive()) return false;
        a.status = AppointmentStatus.CANCELLED;
        a.request.status = RequestStatus.REMOVED;
        release(a);
        if (a.start > now) {
            cancelledFutureMinutes += a.end - a.start;
            fillGap(a.provider, a.start, a.end, now, Set.of());
        }
        return true;
    }

    /** Patient accepts a waitlist offer before it expires. */
    public boolean confirm(int appointmentId, int now) {
        Appointment a = appointments.get(appointmentId);
        if (a == null || a.status != AppointmentStatus.HELD || now >= a.holdExpires) return false;
        a.status = AppointmentStatus.BOOKED;
        a.holdExpires = null;
        a.request.status = RequestStatus.BOOKED;
        refilledMinutes += a.end - a.start;
        return true;
    }

    /** Patient leaves the waitlist (lazy deletion: the heap entry is skipped when popped). */
    public boolean removeFromWaitlist(int requestId) {
        Request r = requests.get(requestId);
        if (r == null || r.status != RequestStatus.WAITING) return false;
        r.status = RequestStatus.REMOVED;
        return true;
    }

    /** Expires overdue holds and offers each freed slot to the next patient in line. */
    public void tick(int now) {
        List<Appointment> expired = new ArrayList<>();
        for (Appointment a : appointments.values())
            if (a.status == AppointmentStatus.HELD && a.holdExpires <= now) expired.add(a);

        for (Appointment a : expired) returnHoldToQueue(a, AppointmentStatus.EXPIRED);
        for (Appointment a : expired)          // don't re-offer the same slot to the patient who let it lapse
            fillGap(a.provider, a.start, a.end, now, Set.of(a.request.id));
    }

    /**
     * Tries to book everyone on the waitlist, highest priority first. Call this once a day:
     * as time moves forward, new days enter each patient's acceptable window.
     */
    public int retryWaitlist(int now) {
        List<Request> stillWaiting = new ArrayList<>();
        int newlyBooked = 0;
        Request r;
        while ((r = popWaitlist()) != null) {
            if (tryBook(r, now) != null) newlyBooked++;
            else stillWaiting.add(r);
        }
        for (Request s : stillWaiting) pushWaitlist(s);
        return newlyBooked;
    }

    // =====================================================================
    // Patient-chosen times (used by the web app)
    // =====================================================================

    /** Is the whole of [start, start + duration) inside one of the provider's working windows? */
    public boolean withinHours(String provider, int start, int duration) {
        int weekday = (start / DAY) % 7, minuteOfDay = start % DAY;
        for (Window w : providers.get(provider).hours().getOrDefault(weekday, List.of()))
            if (minuteOfDay >= w.start() && minuteOfDay + duration <= w.end()) return true;
        return false;
    }

    /** Could a patient book exactly this time right now? */
    public boolean isOpen(String provider, int start, int duration, int now) {
        return start >= now && start % BLOCK == 0
                && withinHours(provider, start, duration) && blocksFree(provider, start, start + duration);
    }

    /** Books exactly the time the patient picked. Returns null (and changes nothing) if it isn't open. */
    public Appointment bookAt(Request req, String provider, int start, int now) {
        if (!isOpen(provider, start, req.duration, now)) return null;
        requests.put(req.id, req);
        Appointment a = createAppointment(req, provider, start, AppointmentStatus.BOOKED);
        a.reason = "patient chose this time";
        req.status = RequestStatus.BOOKED;
        return a;
    }

    /**
     * Puts the patient straight on the waitlist; they'll be offered the next cancellation that fits.
     * Returns false if no working window is ever long enough for them.
     */
    public boolean joinWaitlist(Request req, int now) {
        requests.put(req.id, req);
        if (!everPossible(req)) {
            req.status = RequestStatus.UNSCHEDULABLE;
            return false;
        }
        req.status = RequestStatus.WAITING;
        pushWaitlist(req);
        return true;
    }

    /** Every open start time for a visit of this length in [from, to), earliest first. */
    public List<Integer> openStarts(String provider, int duration, int from, int to, int now) {
        List<Integer> out = new ArrayList<>();
        for (int s = roundUpToBlock(Math.max(from, now)); s + duration <= to; s += BLOCK)
            if (isOpen(provider, s, duration, now)) out.add(s);
        return out;
    }

    /** Replaces a provider's weekly hours. Existing appointments are kept even if now outside them. */
    public void setHours(String provider, Map<Integer, List<Window>> hours) {
        Provider p = providers.get(provider);
        if (p == null) throw new IllegalArgumentException("Unknown provider: " + provider);
        providers.put(provider, new Provider(provider, p.specialty(), Map.copyOf(hours)));
    }

    /** Patient turns down a waitlist offer: they keep their place in line and the slot goes to the next person. */
    public boolean declineHold(int appointmentId, int now) {
        Appointment a = appointments.get(appointmentId);
        if (a == null || a.status != AppointmentStatus.HELD) return false;
        returnHoldToQueue(a, AppointmentStatus.DECLINED);
        fillGap(a.provider, a.start, a.end, now, Set.of(a.request.id));
        return true;
    }

    public void setUrgentReserve(boolean on) { urgentReserve = on; }

    private void returnHoldToQueue(Appointment a, AppointmentStatus newStatus) {
        a.status = newStatus;
        release(a);
        a.request.status = RequestStatus.WAITING;
        pushWaitlist(a.request);               // keeps their original place in line
    }

    // =====================================================================
    // Booking
    // =====================================================================

    private Appointment tryBook(Request req, int now) {
        Slot best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Slot s : findSlots(req, now)) {
            double score = slotScore(req, s.start(), now);
            if (score > bestScore) { bestScore = score; best = s; }
        }
        if (best == null) return null;
        Appointment a = createAppointment(req, best.provider(), best.start(), AppointmentStatus.BOOKED);
        a.reason = "best-scoring open slot";
        req.status = RequestStatus.BOOKED;
        return a;
    }

    /**
     * Could this request EVER be booked, even with an empty calendar? Checks that some provider
     * with the right specialty works on an allowed weekday in a window long enough.
     * Without this, impossible requests would sit on the waitlist forever.
     */
    boolean everPossible(Request req) {
        for (Provider p : providers.values()) {
            if (!p.specialty().equals(req.specialty)) continue;
            for (Map.Entry<Integer, List<Window>> day : p.hours().entrySet()) {
                if (req.allowedDays != null && !req.allowedDays.contains(day.getKey())) continue;
                for (Window w : day.getValue())
                    if (w.end() - w.start() >= req.duration) return true;
            }
        }
        return false;
    }

    /** Every open start time that satisfies the request's hard constraints. */
    List<Slot> findSlots(Request req, int now) {
        List<Slot> slots = new ArrayList<>();
        int latestStart = now + MAX_WAIT_DAYS[req.urgency] * DAY;
        for (int day = now / DAY; day * DAY <= latestStart; day++) {
            for (Provider p : providers.values()) {
                for (Window w : p.hours().getOrDefault(day % 7, List.of())) {
                    for (int t = w.start(); t + req.duration <= w.end(); t += BLOCK) {
                        int start = day * DAY + t;
                        if (acceptable(req, p, start, now) && blocksFree(p.name(), start, start + req.duration))
                            slots.add(new Slot(p.name(), start));
                    }
                }
            }
        }
        return slots;
    }

    /**
     * Hard constraints: right specialty, not in the past, within the urgency's max wait,
     * not urgent-reserved time (unless urgent or close enough to be released), on an allowed day.
     */
    private boolean acceptable(Request req, Provider p, int start, int now) {
        if (!p.specialty().equals(req.specialty)) return false;
        if (start < now || start > now + MAX_WAIT_DAYS[req.urgency] * DAY) return false;
        if (urgentReserve && isReserved(start)) {
            int lead = start - now;
            boolean open = lead <= RESERVE_RELEASE
                    || (req.urgency >= URGENT_LEVEL && lead <= URGENT_LOOKAHEAD);
            if (!open) return false;
        }
        int weekday = (start / DAY) % 7;
        return req.allowedDays == null || req.allowedDays.contains(weekday);
    }

    /**
     * Soft scoring (higher is better). Waiting costs 5 * urgency^2 points per day,
     * so urgent patients strongly prefer the earliest slot, while routine patients
     * will wait a little longer for their preferred time of day.
     */
    double slotScore(Request req, int start, int now) {
        double daysAway = (start - now) / (double) DAY;
        double score = -daysAway * 5 * req.urgency * req.urgency;
        if (req.prefersMorning != null) {
            boolean isMorning = start % DAY < MORNING_END;
            if (isMorning == req.prefersMorning) score += PREFERENCE_BONUS;
        }
        return score;
    }

    // =====================================================================
    // Cancellation fill
    // =====================================================================

    /** Offers the free time [gapStart, gapEnd) to the highest-priority patients that fit, as holds. */
    private void fillGap(String provider, int gapStart, int gapEnd, int now, Set<Integer> exclude) {
        Provider p = providers.get(provider);
        int start = Math.max(gapStart, roundUpToBlock(now));
        while (start < gapEnd) {
            Request chosen = null;
            List<Request> skipped = new ArrayList<>();
            Request r;
            while ((r = popWaitlist()) != null) {
                if (!exclude.contains(r.id) && start + r.duration <= gapEnd
                        && acceptable(r, p, start, now) && blocksFree(provider, start, start + r.duration)) {
                    chosen = r;
                    break;
                }
                skipped.add(r);                // doesn't fit this gap; goes back in line
            }
            for (Request s : skipped) pushWaitlist(s);
            if (chosen == null) return;

            Appointment hold = createAppointment(chosen, provider, start, AppointmentStatus.HELD);
            hold.holdExpires = now + HOLD_MINUTES;
            hold.reason = "offered from waitlist (highest priority that fit)";
            chosen.status = RequestStatus.OFFERED;
            start += chosen.duration;          // leftover time in the gap can go to the next patient
        }
    }

    // =====================================================================
    // Blocks: the double-booking guarantee
    // =====================================================================

    public boolean blocksFree(String provider, int start, int end) {
        Map<Integer, Integer> taken = booked.get(provider);
        for (int b = start / BLOCK; b < end / BLOCK; b++)
            if (taken.containsKey(b)) return false;
        return true;
    }

    /** The ONLY place blocks are taken. Checks first, then claims all of them. */
    void claim(String provider, int start, int end, int appointmentId) {
        if (start % BLOCK != 0 || end % BLOCK != 0 || end <= start)
            throw new IllegalArgumentException("Times must be aligned to 15-minute blocks");
        if (!blocksFree(provider, start, end))
            throw new IllegalStateException("Double booking prevented for " + provider + " at " + format(start));
        Map<Integer, Integer> taken = booked.get(provider);
        for (int b = start / BLOCK; b < end / BLOCK; b++) taken.put(b, appointmentId);
    }

    private void release(Appointment a) {
        Map<Integer, Integer> taken = booked.get(a.provider);
        for (int b = a.start / BLOCK; b < a.end / BLOCK; b++)
            taken.remove(b, a.id);             // only removes blocks this appointment owns
    }

    private Appointment createAppointment(Request req, String provider, int start, AppointmentStatus status) {
        Appointment a = new Appointment(nextAppointmentId++, req, provider, start, start + req.duration);
        claim(provider, a.start, a.end, a.id); // throws before anything is recorded if blocks are taken
        a.status = status;
        appointments.put(a.id, a);
        return a;
    }

    // =====================================================================
    // Waitlist: priority queue with aging and lazy deletion
    // =====================================================================

    /**
     * priority(now) = URGENCY_WEIGHT * urgency + agingRate * (now - createdAt).
     * The agingRate * now term is the same for every patient, so it never changes who is
     * ahead of whom. Ordering only depends on URGENCY_WEIGHT * urgency - agingRate * createdAt,
     * which is fixed. Negated because PriorityQueue is a min-heap.
     */
    long priorityKey(Request r) {
        return agingRate * r.createdAt - URGENCY_WEIGHT * r.urgency;
    }

    private void pushWaitlist(Request r) {
        if (r.queueSeq < 0) r.queueSeq = nextSeq++;   // first time in line; keep this place forever
        waitlist.add(new WaitEntry(priorityKey(r), r.queueSeq, r));
    }

    /** Pops the highest-priority patient still waiting, skipping stale entries. */
    private Request popWaitlist() {
        while (!waitlist.isEmpty()) {
            Request r = waitlist.poll().request();
            if (r.status == RequestStatus.WAITING) return r;
        }
        return null;
    }

    // =====================================================================
    // Read-only views and helpers
    // =====================================================================

    public Appointment getAppointment(int id) { return appointments.get(id); }

    public Request getRequest(int id) { return requests.get(id); }

    /** The booked or held appointment for this request, or null. */
    public Appointment activeAppointmentFor(int requestId) {
        for (Appointment a : appointments.values())
            if (a.request.id == requestId && a.isActive()) return a;
        return null;
    }

    /** Id of the appointment occupying this minute for the provider, or null if free. */
    public Integer appointmentAt(String provider, int minute) {
        return booked.get(provider).get(minute / BLOCK);
    }

    /** 1-based place in line, or 0 if not waiting. */
    public int waitlistPosition(int requestId) {
        List<Request> q = waitlistInOrder();
        for (int i = 0; i < q.size(); i++)
            if (q.get(i).id == requestId) return i + 1;
        return 0;
    }

    public Collection<Appointment> getAppointments() { return Collections.unmodifiableCollection(appointments.values()); }

    public Collection<Provider> getProviders() { return Collections.unmodifiableCollection(providers.values()); }

    /** Patients still waiting, in the order they would be served. */
    public List<Request> waitlistInOrder() {
        PriorityQueue<WaitEntry> copy = new PriorityQueue<>(waitlist);
        List<Request> out = new ArrayList<>();
        while (!copy.isEmpty()) {
            Request r = copy.poll().request();
            if (r.status == RequestStatus.WAITING) out.add(r);
        }
        return out;
    }

    public int getCancelledFutureMinutes() { return cancelledFutureMinutes; }

    public int getRefilledMinutes() { return refilledMinutes; }

    static boolean isReserved(int start) {
        return start % DAY >= RESERVED_FROM;
    }

    private static int roundUpToBlock(int minute) {
        return ((minute + BLOCK - 1) / BLOCK) * BLOCK;
    }

    private static final String[] DAY_NAMES = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    /** e.g. 1980 -> "Day 1 (Tue) 09:00" */
    public static String format(int minute) {
        int day = minute / DAY, m = minute % DAY;
        return String.format("Day %d (%s) %02d:%02d", day, DAY_NAMES[day % 7], m / 60, m % 60);
    }
}
