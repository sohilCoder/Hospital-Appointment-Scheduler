import java.util.*;

/**
 * Simulates an overloaded clinic for 90 days and reports wait times by urgency,
 * then compares aging strengths on identical patient traffic.
 *
 * Usage: java Simulate [seed]
 */
public class Simulate {

    static final int DAYS = 90;
    static final int BASE_REQUESTS_PER_DAY = 24; // plus 0-12 more; slightly above capacity
    static final double CANCEL_CHANCE = 0.04;    // per 15-minute step, chance someone cancels
    static final double CONFIRM_CHANCE = 0.15;   // per 15-minute step, chance a held offer is accepted
    static final long[] AGING_RATES = {0, 1, 7}; // none, 1 urgency level per week, 1 level per day
    static final String[] AGING_NAMES = {"No aging", "Gentle (1 level/week)", "Strong (1 level/day)"};
    static final int COMPARISON_SEEDS = 10;

    record Result(Scheduler scheduler, List<Scheduler.Request> requests, int endTime) {}

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42;
        System.out.println("Clinic scheduler simulation: " + DAYS + " days, seed " + seed
                + ", demand slightly above capacity\n");

        System.out.println("=== Detailed results, gentle aging ===");
        report(run(seed, AGING_RATES[1]));

        System.out.println("\n=== Aging comparison, averaged over " + COMPARISON_SEEDS + " seeds ===");
        compareAging();
    }

    /** Runs every aging setting on the same traffic and prints average key metrics. */
    static void compareAging() {
        System.out.printf("%-22s %16s %16s %16s %16s%n", "Setting",
                "Urgency 5:", "Urgency 4-5:", "Urgency 1-3:", "Still waiting");
        System.out.printf("%-22s %16s %16s %16s %16s%n", "",
                "avg days to appt", "worst wait", "worst wait", "at end");
        for (int i = 0; i < AGING_RATES.length; i++) {
            double u5Avg = 0, urgentWorst = 0, routineWorst = 0, waiting = 0;
            for (long seed = 1; seed <= COMPARISON_SEEDS; seed++) {
                Result r = run(seed, AGING_RATES[i]);
                u5Avg += avgDaysToAppointment(r, 5, 5);
                urgentWorst += worstWait(r, 4, 5);
                routineWorst += worstWait(r, 1, 3);
                waiting += r.scheduler().waitlistInOrder().size();
            }
            int n = COMPARISON_SEEDS;
            System.out.printf("%-22s %16.1f %16.1f %16.1f %16.1f%n",
                    AGING_NAMES[i], u5Avg / n, urgentWorst / n, routineWorst / n, waiting / n);
        }
        System.out.println("Worst wait = longest time from request to appointment, or to the end of the run if never booked.");
    }

    static double avgDaysToAppointment(Result r, int minUrgency, int maxUrgency) {
        Map<Integer, Scheduler.Appointment> appts = bookedByRequest(r);
        double sum = 0;
        int count = 0;
        for (Scheduler.Request req : r.requests()) {
            if (req.urgency < minUrgency || req.urgency > maxUrgency) continue;
            Scheduler.Appointment a = appts.get(req.id);
            if (a != null) { sum += (a.start - req.createdAt) / (double) Scheduler.DAY; count++; }
        }
        return count == 0 ? 0 : sum / count;
    }

    static double worstWait(Result r, int minUrgency, int maxUrgency) {
        Map<Integer, Scheduler.Appointment> appts = bookedByRequest(r);
        double worst = 0;
        for (Scheduler.Request req : r.requests()) {
            if (req.urgency < minUrgency || req.urgency > maxUrgency) continue;
            Scheduler.Appointment a = appts.get(req.id);
            if (a != null) worst = Math.max(worst, (a.start - req.createdAt) / (double) Scheduler.DAY);
            else if (stillWaiting(req)) worst = Math.max(worst, (r.endTime() - req.createdAt) / (double) Scheduler.DAY);
        }
        return worst;
    }

    static Map<Integer, Scheduler.Appointment> bookedByRequest(Result r) {
        Map<Integer, Scheduler.Appointment> out = new HashMap<>();
        for (Scheduler.Appointment a : r.scheduler().getAppointments())
            if (a.status == Scheduler.AppointmentStatus.BOOKED) out.put(a.request.id, a);
        return out;
    }

    static boolean stillWaiting(Scheduler.Request req) {
        return req.status == Scheduler.RequestStatus.WAITING || req.status == Scheduler.RequestStatus.OFFERED;
    }

    static List<Scheduler.Provider> clinic() {
        Scheduler.Window morning = new Scheduler.Window(8 * 60, 12 * 60);
        Scheduler.Window afternoon = new Scheduler.Window(13 * 60, 17 * 60);
        Scheduler.Window lateMorning = new Scheduler.Window(9 * 60, 12 * 60);
        Scheduler.Window earlyAfternoon = new Scheduler.Window(13 * 60, 16 * 60);

        Map<Integer, List<Scheduler.Window>> patel = new HashMap<>();
        for (int d = 0; d <= 4; d++) patel.put(d, List.of(morning, afternoon));     // Mon-Fri
        Map<Integer, List<Scheduler.Window>> nguyen = new HashMap<>();
        for (int d = 0; d <= 3; d++) nguyen.put(d, List.of(lateMorning, earlyAfternoon)); // Mon-Thu
        Map<Integer, List<Scheduler.Window>> okafor = new HashMap<>();
        for (int d : new int[]{0, 2, 4}) okafor.put(d, List.of(morning, afternoon)); // Mon/Wed/Fri

        return List.of(
                new Scheduler.Provider("Dr. Patel", "family medicine", patel),
                new Scheduler.Provider("Dr. Nguyen", "family medicine", nguyen),
                new Scheduler.Provider("Dr. Okafor", "cardiology", okafor));
    }

    /** Generates the same patient traffic for a given seed, independent of scheduler behavior. */
    static List<Scheduler.Request> generateRequests(long seed) {
        Random rng = new Random(seed);
        int[] durations = {15, 30, 45, 60};
        double[] durationOdds = {0.25, 0.40, 0.20, 0.15};
        double[] urgencyOdds = {0.30, 0.30, 0.20, 0.12, 0.08};  // urgency 1..5

        List<Scheduler.Request> out = new ArrayList<>();
        int id = 1;
        for (int day = 0; day < DAYS; day++) {
            int count = BASE_REQUESTS_PER_DAY + rng.nextInt(13);
            for (int i = 0; i < count; i++) {
                int created = day * Scheduler.DAY + 7 * 60 + rng.nextInt(14 * 60);  // 7:00-21:00
                String specialty = rng.nextDouble() < 0.75 ? "family medicine" : "cardiology";
                int duration = durations[pick(rng, durationOdds)];
                int urgency = pick(rng, urgencyOdds) + 1;

                Set<Integer> allowedDays = null;               // 20% can only come on certain weekdays
                if (rng.nextDouble() < 0.20) {
                    allowedDays = new HashSet<>();
                    while (allowedDays.size() < 2 + rng.nextInt(2)) allowedDays.add(rng.nextInt(5));
                }
                double p = rng.nextDouble();
                Boolean prefersMorning = p < 0.4 ? Boolean.TRUE : p < 0.7 ? Boolean.FALSE : null;

                out.add(new Scheduler.Request(id, "Patient " + id, specialty, duration, urgency,
                        created, allowedDays, prefersMorning));
                id++;
            }
        }
        out.sort(Comparator.comparingInt(r -> r.createdAt));
        return out;
    }

    static Result run(long seed, long agingRate) {
        Scheduler s = new Scheduler(clinic(), agingRate);
        List<Scheduler.Request> requests = generateRequests(seed);
        Random rng = new Random(seed + 1);
        int next = 0;
        int end = DAYS * Scheduler.DAY;

        for (int now = 0; now < end; now += Scheduler.BLOCK) {
            if (now % Scheduler.DAY == 0) s.retryWaitlist(now);     // new days enter everyone's window

            while (next < requests.size() && requests.get(next).createdAt <= now)
                s.request(requests.get(next++), now);

            if (rng.nextDouble() < CANCEL_CHANCE) {                // someone with a future booking cancels
                List<Scheduler.Appointment> future = new ArrayList<>();
                for (Scheduler.Appointment a : s.getAppointments())
                    if (a.status == Scheduler.AppointmentStatus.BOOKED && a.start > now) future.add(a);
                if (!future.isEmpty()) s.cancel(future.get(rng.nextInt(future.size())).id, now);
            }

            for (Scheduler.Appointment a : new ArrayList<>(s.getAppointments()))  // patients answer offers
                if (a.status == Scheduler.AppointmentStatus.HELD && rng.nextDouble() < CONFIRM_CHANCE)
                    s.confirm(a.id, now);

            s.tick(now);
        }
        return new Result(s, requests, end);
    }

    static void report(Result r) {
        Map<Integer, Scheduler.Appointment> apptByRequest = new HashMap<>();
        for (Scheduler.Appointment a : r.scheduler().getAppointments())
            if (a.status == Scheduler.AppointmentStatus.BOOKED) apptByRequest.put(a.request.id, a);

        System.out.printf("%-8s %8s %8s %10s %11s %13s %13s %9s%n", "Urgency", "Requests", "Booked",
                "Patient", "Rejected", "Avg days to", "Max days to", "Still");
        System.out.printf("%-8s %8s %8s %10s %11s %13s %13s %9s%n", "", "", "",
                "cancelled", "(impossible)", "appointment", "appointment", "waiting");
        int rejectedTotal = 0;
        for (int u = 5; u >= 1; u--) {
            int total = 0, bookedCount = 0, cancelled = 0, rejected = 0, waiting = 0;
            double sumDays = 0, maxDays = 0;
            for (Scheduler.Request req : r.requests()) {
                if (req.urgency != u) continue;
                total++;
                Scheduler.Appointment a = apptByRequest.get(req.id);
                if (a != null) {
                    bookedCount++;
                    double days = (a.start - req.createdAt) / (double) Scheduler.DAY;
                    sumDays += days;
                    maxDays = Math.max(maxDays, days);
                } else if (stillWaiting(req)) waiting++;
                else if (req.status == Scheduler.RequestStatus.UNSCHEDULABLE) rejected++;
                else cancelled++;
            }
            rejectedTotal += rejected;
            System.out.printf("%-8d %8d %8d %10d %11d %13.1f %13.1f %9d%n", u, total, bookedCount,
                    cancelled, rejected, bookedCount == 0 ? 0 : sumDays / bookedCount, maxDays, waiting);
        }
        System.out.println("Rejected requests (" + rejectedTotal + ") asked for times no provider ever works, "
                + "e.g. cardiology on a day the cardiologist is off.");
        int freed = r.scheduler().getCancelledFutureMinutes();
        System.out.printf("Cancelled time refilled from the waitlist: %.0f%% (%d of %d minutes)%n",
                freed == 0 ? 0 : 100.0 * r.scheduler().getRefilledMinutes() / freed,
                r.scheduler().getRefilledMinutes(), freed);
    }

    private static int pick(Random rng, double[] odds) {
        double x = rng.nextDouble(), sum = 0;
        for (int i = 0; i < odds.length; i++) {
            sum += odds[i];
            if (x < sum) return i;
        }
        return odds.length - 1;
    }
}
