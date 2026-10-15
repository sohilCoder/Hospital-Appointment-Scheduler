import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

    static final int DAY = Scheduler.DAY;
    static final int MON = 0, TUE = 1;
    static int nextId = 1;

    // ----- helpers -----

    static Scheduler.Window window(int fromHour, int fromMinute, int toHour, int toMinute) {
        return new Scheduler.Window(fromHour * 60 + fromMinute, toHour * 60 + toMinute);
    }

    static Scheduler.Provider provider(String name, String specialty, Map<Integer, List<Scheduler.Window>> hours) {
        return new Scheduler.Provider(name, specialty, hours);
    }

    /** One family-medicine provider working the given window every Monday. */
    static Scheduler mondayClinic(Scheduler.Window w, long agingRate) {
        return new Scheduler(List.of(provider("Dr. A", "family", Map.of(MON, List.of(w)))), agingRate);
    }

    static Scheduler.Request req(int urgency, int duration, int createdAt) {
        return req(urgency, duration, createdAt, null, null);
    }

    static Scheduler.Request req(int urgency, int duration, int createdAt, Set<Integer> days, Boolean morning) {
        int id = nextId++;
        return new Scheduler.Request(id, "P" + id, "family", duration, urgency, createdAt, days, morning);
    }

    /** Books routine filler patients until every slot within a 30-day window is taken. */
    static void fillCalendar(Scheduler s, int duration, int now) {
        while (true) {
            Scheduler.Request filler = req(1, duration, now);
            if (s.request(filler, now) == null) {
                s.removeFromWaitlist(filler.id);
                return;
            }
        }
    }

    // ----- booking -----

    @Test
    void booksEarliestSlotForUrgentPatient() {
        Scheduler s = mondayClinic(window(9, 0, 10, 0), 1);
        Scheduler.Appointment a = s.request(req(5, 30, 0), 0);
        assertNotNull(a);
        assertEquals(9 * 60, a.start);
        assertEquals(Scheduler.AppointmentStatus.BOOKED, a.status);
    }

    @Test
    void secondRequestForOnlySlotGoesToWaitlist() {
        Scheduler s = mondayClinic(window(9, 0, 9, 30), 1);
        Scheduler.Request first = req(5, 30, 0), second = req(5, 30, 0);
        assertNotNull(s.request(first, 0));
        assertNull(s.request(second, 0));
        assertEquals(List.of(second), s.waitlistInOrder());
    }

    @Test
    void claimRefusesOverlappingTimeAndChangesNothing() {
        Scheduler s = mondayClinic(window(9, 0, 10, 0), 1);
        s.request(req(5, 30, 0), 0);                               // takes 9:00-9:30
        assertThrows(IllegalStateException.class, () -> s.claim("Dr. A", 9 * 60 + 15, 9 * 60 + 45, 999));
        assertTrue(s.blocksFree("Dr. A", 9 * 60 + 30, 9 * 60 + 45)); // failed claim took nothing
    }

    @Test
    void allowedDaysAreNeverViolated() {
        Scheduler s = new Scheduler(List.of(provider("Dr. A", "family",
                Map.of(MON, List.of(window(9, 0, 10, 0)), TUE, List.of(window(9, 0, 10, 0))))));
        Scheduler.Appointment a = s.request(req(5, 30, 0, Set.of(TUE), null), 0);
        assertEquals(DAY + 9 * 60, a.start);                        // Tuesday, though Monday was open
    }

    @Test
    void routinePatientGetsPreferredTimeOfDay() {
        Scheduler s = new Scheduler(List.of(provider("Dr. A", "family",
                Map.of(MON, List.of(window(9, 0, 10, 0), window(13, 0, 14, 0))))));
        Scheduler.Appointment a = s.request(req(1, 30, 0, null, false), 0);  // prefers afternoon
        assertEquals(13 * 60, a.start);
    }

    @Test
    void impossibleRequestIsRejectedInsteadOfWaitingForever() {
        Scheduler s = mondayClinic(window(9, 0, 10, 0), 1);
        Scheduler.Request r = req(3, 30, 0, Set.of(TUE), null);     // provider never works Tuesdays
        assertNull(s.request(r, 0));
        assertEquals(Scheduler.RequestStatus.UNSCHEDULABLE, r.status);
        assertTrue(s.waitlistInOrder().isEmpty());
    }

    @Test
    void reservedAfternoonTimeIsKeptForUrgentPatientsUntilReleased() {
        Scheduler s = new Scheduler(List.of(provider("Dr. A", "family",
                Map.of(TUE, List.of(window(15, 0, 16, 0))))));         // all reserved time
        Scheduler.Request routine = req(1, 30, 0), urgent = req(5, 30, 0);
        assertNull(s.request(routine, 0));                         // 39 hours ahead: still reserved
        Scheduler.Appointment u = s.request(urgent, 0);
        assertEquals(DAY + 15 * 60, u.start);                       // urgent patient gets it

        int lessThanADayBefore = DAY + 15 * 60 - DAY + 60;          // Monday 16:00
        assertEquals(1, s.retryWaitlist(lessThanADayBefore));       // now released to everyone
        assertEquals(Scheduler.RequestStatus.BOOKED, routine.status);
    }

    // ----- waitlist priority -----

    @Test
    void waitlistServesHigherUrgencyFirst() {
        Scheduler s = mondayClinic(window(9, 0, 9, 15), 1);
        fillCalendar(s, 15, 0);
        Scheduler.Request u2 = req(2, 15, 0), u5 = req(5, 15, 0), u3 = req(3, 15, 0);
        s.request(u2, 0);
        s.request(u5, 0);
        s.request(u3, 0);
        assertEquals(List.of(u5, u3, u2), s.waitlistInOrder());
    }

    @Test
    void equalPriorityIsFirstComeFirstServed() {
        Scheduler s = mondayClinic(window(9, 0, 9, 15), 1);
        fillCalendar(s, 15, 0);
        Scheduler.Request a = req(3, 15, 0), b = req(3, 15, 0);
        s.request(a, 0);
        s.request(b, 0);
        assertEquals(List.of(a, b), s.waitlistInOrder());
    }

    @Test
    void agingLetsALongWaitOutrankHigherUrgency() {
        // With agingRate 1, one urgency level = 7 days of waiting.
        // Urgency 1 waiting 8 days should now outrank a brand-new urgency 2.
        int now = 8 * DAY;
        Scheduler.Request oldRoutine = req(1, 15, 0), newer = req(2, 15, now);

        Scheduler withAging = mondayClinic(window(9, 0, 9, 15), 1);
        fillCalendar(withAging, 15, now);
        withAging.request(oldRoutine, now);
        withAging.request(newer, now);
        assertEquals(List.of(oldRoutine, newer), withAging.waitlistInOrder());

        Scheduler.Request oldRoutine2 = req(1, 15, 0), newer2 = req(2, 15, now);
        Scheduler noAging = mondayClinic(window(9, 0, 9, 15), 0);
        fillCalendar(noAging, 15, now);
        noAging.request(oldRoutine2, now);
        noAging.request(newer2, now);
        assertEquals(List.of(newer2, oldRoutine2), noAging.waitlistInOrder());
    }

    @Test
    void priorityKeyNeverNeedsUpdating() {
        // The ordering between two patients is the same no matter what time it is.
        Scheduler s = mondayClinic(window(9, 0, 9, 15), 1);
        Scheduler.Request a = req(3, 15, 100), b = req(2, 15, 5000);
        for (int now = 6000; now < 60 * DAY; now += DAY) {
            long prioA = Scheduler.URGENCY_WEIGHT * a.urgency + (now - a.createdAt);
            long prioB = Scheduler.URGENCY_WEIGHT * b.urgency + (now - b.createdAt);
            assertEquals(Long.signum(prioB - prioA), Long.signum(s.priorityKey(a) - s.priorityKey(b)));
        }
    }

    @Test
    void removedPatientIsSkippedByLazyDeletion() {
        Scheduler s = mondayClinic(window(9, 0, 9, 15), 1);
        fillCalendar(s, 15, 0);
        Scheduler.Request top = req(5, 15, 0), next = req(3, 15, 0);
        s.request(top, 0);
        s.request(next, 0);
        assertTrue(s.removeFromWaitlist(top.id));
        assertEquals(List.of(next), s.waitlistInOrder());
    }

    // ----- cancellations and holds -----

    /** Monday 9:00-10:00, fully booked by A (9:00) and B (9:30), with three waitlisted patients. */
    static class FullDay {
        final Scheduler s = mondayClinic(window(9, 0, 10, 0), 1);
        final Scheduler.Appointment a, b;
        final Scheduler.Request tooLong = req(5, 60, 0);   // highest priority, but won't fit a 30-min gap
        final Scheduler.Request fits1 = req(4, 30, 0);
        final Scheduler.Request fits2 = req(4, 30, 0);

        FullDay() {
            a = s.request(req(5, 30, 0), 0);
            b = s.request(req(5, 30, 0), 0);
            assertNull(s.request(tooLong, 0));
            assertNull(s.request(fits1, 0));
            assertNull(s.request(fits2, 0));
        }

        Scheduler.Appointment holdFor(Scheduler.Request r) {
            return s.getAppointments().stream()
                    .filter(x -> x.request == r && x.status == Scheduler.AppointmentStatus.HELD)
                    .findFirst().orElse(null);
        }
    }

    @Test
    void cancellationOffersSlotToHighestPriorityPatientWhoFits() {
        FullDay d = new FullDay();
        assertTrue(d.s.cancel(d.a.id, 0));
        Scheduler.Appointment hold = d.holdFor(d.fits1);
        assertNotNull(hold);
        assertEquals(9 * 60, hold.start);
        assertEquals(Scheduler.HOLD_MINUTES, hold.holdExpires);
        assertEquals(Scheduler.RequestStatus.WAITING, d.tooLong.status);  // skipped, still in line
        assertEquals(List.of(d.tooLong, d.fits2), d.s.waitlistInOrder());
    }

    @Test
    void confirmedHoldBecomesBooking() {
        FullDay d = new FullDay();
        d.s.cancel(d.a.id, 0);
        Scheduler.Appointment hold = d.holdFor(d.fits1);
        assertTrue(d.s.confirm(hold.id, 60));
        assertEquals(Scheduler.AppointmentStatus.BOOKED, hold.status);
        assertEquals(Scheduler.RequestStatus.BOOKED, d.fits1.status);
    }

    @Test
    void expiredHoldMovesToNextPatient() {
        FullDay d = new FullDay();
        d.s.cancel(d.a.id, 0);
        Scheduler.Appointment first = d.holdFor(d.fits1);

        d.s.tick(Scheduler.HOLD_MINUTES);                     // fits1 never answered
        assertEquals(Scheduler.AppointmentStatus.EXPIRED, first.status);
        assertFalse(d.s.confirm(first.id, Scheduler.HOLD_MINUTES));
        assertNotNull(d.holdFor(d.fits2));                    // offered to the next patient
        assertEquals(Scheduler.RequestStatus.WAITING, d.fits1.status);  // fits1 keeps a place in line
    }

    @Test
    void retryBooksWaitlistedPatientWhenANewDayEntersTheirWindow() {
        Scheduler s = mondayClinic(window(9, 0, 9, 30), 1);
        s.request(req(4, 30, 0), 0);                          // takes this Monday
        Scheduler.Request waiting = req(4, 30, 0);            // next Monday is 7.4 days away: too far
        assertNull(s.request(waiting, 0));
        assertEquals(1, s.retryWaitlist(DAY));                // on Tuesday, next Monday is within 7 days
        assertEquals(Scheduler.RequestStatus.BOOKED, waiting.status);
    }

    // ----- patient-chosen times (web app) -----

    @Test
    void bookAtBooksExactTimeOnlyWhenOpen() {
        Scheduler s = mondayClinic(window(9, 0, 10, 0), 1);
        Scheduler.Appointment a = s.bookAt(req(1, 30, 0), "Dr. A", 9 * 60 + 15, 0);
        assertEquals(9 * 60 + 15, a.start);
        assertNull(s.bookAt(req(1, 30, 0), "Dr. A", 9 * 60 + 30, 0));   // overlaps 9:15-9:45
        assertNull(s.bookAt(req(1, 30, 0), "Dr. A", 9 * 60 + 45, 0));   // runs past closing
        assertNull(s.bookAt(req(1, 30, 0), "Dr. A", 9 * 60, 9 * 60 + 5)); // already started
        assertNotNull(s.bookAt(req(1, 15, 0), "Dr. A", 9 * 60 + 45, 0));
    }

    @Test
    void openStartsListsOnlyFreeTimesThatFit() {
        Scheduler s = mondayClinic(window(9, 0, 10, 0), 1);
        s.bookAt(req(1, 30, 0), "Dr. A", 9 * 60 + 15, 0);               // 9:15-9:45 taken
        assertEquals(List.of(9 * 60, 9 * 60 + 45), s.openStarts("Dr. A", 15, 0, DAY, 0));
        assertEquals(List.of(), s.openStarts("Dr. A", 30, 0, DAY, 0));
    }

    @Test
    void setHoursChangesWhatCanBeBooked() {
        Scheduler s = mondayClinic(window(9, 0, 10, 0), 1);
        assertFalse(s.withinHours("Dr. A", DAY + 9 * 60, 30));           // closed Tuesday
        s.setHours("Dr. A", Map.of(TUE, List.of(window(9, 0, 10, 0))));
        assertTrue(s.withinHours("Dr. A", DAY + 9 * 60, 30));
        assertFalse(s.withinHours("Dr. A", 9 * 60, 30));                 // Monday now closed
    }

    @Test
    void joinWaitlistRejectsVisitLongerThanAnyOpenDay() {
        Scheduler s = mondayClinic(window(9, 0, 10, 0), 1);
        assertFalse(s.joinWaitlist(req(1, 90, 0), 0));
        Scheduler.Request ok = req(1, 60, 0);
        assertTrue(s.joinWaitlist(ok, 0));
        assertEquals(1, s.waitlistPosition(ok.id));
    }

    @Test
    void declinedOfferGoesToNextPatientAndDeclinerKeepsTheirPlace() {
        FullDay d = new FullDay();
        d.s.cancel(d.a.id, 0);
        Scheduler.Appointment first = d.holdFor(d.fits1);
        assertTrue(d.s.declineHold(first.id, 10));
        assertEquals(Scheduler.AppointmentStatus.DECLINED, first.status);
        assertNotNull(d.holdFor(d.fits2));
        assertEquals(List.of(d.tooLong, d.fits1), d.s.waitlistInOrder());  // fits1 back in its old spot
    }

    // ----- whole-system invariants over simulated months -----

    @Test
    void simulatedMonthsNeverBreakTheRules() {
        for (long seed = 1; seed <= 3; seed++) {
            Simulate.Result r = Simulate.run(seed, 1);
            Map<String, Scheduler.Provider> providers = new HashMap<>();
            for (Scheduler.Provider p : r.scheduler().getProviders()) providers.put(p.name(), p);

            Map<String, List<Scheduler.Appointment>> byProvider = new HashMap<>();
            for (Scheduler.Appointment a : r.scheduler().getAppointments()) {
                if (!a.isActive()) continue;
                Scheduler.Provider p = providers.get(a.provider);
                int weekday = (a.start / DAY) % 7;

                assertEquals(p.specialty(), a.request.specialty, "wrong specialty: " + a);
                if (a.request.allowedDays != null)
                    assertTrue(a.request.allowedDays.contains(weekday), "disallowed day: " + a);
                boolean insideHours = p.hours().getOrDefault(weekday, List.of()).stream()
                        .anyMatch(w -> a.start % DAY >= w.start() && (a.start % DAY) + (a.end - a.start) <= w.end());
                assertTrue(insideHours, "outside working hours: " + a);
                byProvider.computeIfAbsent(a.provider, k -> new ArrayList<>()).add(a);
            }

            for (List<Scheduler.Appointment> list : byProvider.values()) {
                list.sort(Comparator.comparingInt(x -> x.start));
                for (int i = 1; i < list.size(); i++)
                    assertTrue(list.get(i - 1).end <= list.get(i).start,
                            "overlap: " + list.get(i - 1) + " and " + list.get(i));
            }
        }
    }
}
