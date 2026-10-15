# Clinic Appointment Scheduler

A clinic scheduling engine in plain Java. It assigns patients to appointment slots based on provider availability, appointment length, urgency, and patient preferences, keeps a priority-queue waitlist, and automatically offers cancelled time to the next patient who fits.

No frameworks and no database: one core class, one simulation, one test file.

## Features

- **Smart booking.** Each request is matched to the best open slot using hard constraints (specialty, allowed days, maximum acceptable wait for its urgency) and a soft score (how soon, and whether it matches a morning/afternoon preference).
- **Guaranteed no double-booking.** Provider time is split into 15-minute blocks. Every booking goes through one method, `claim()`, which checks and takes all blocks together or throws.
- **Priority waitlist with aging.** Urgent patients go first, and priority grows the longer someone waits, so nobody is stuck at the back forever.
- **Cancellation auto-fill with expiring holds.** A cancelled slot is offered to the highest-priority waitlisted patient who fits. The offer is held for 2 hours; if it isn't accepted, it moves to the next patient.
- **Reserved urgent capacity.** Late-afternoon time is kept for urgent patients and released to everyone within 24 hours so it isn't wasted.
- **Impossible-request detection.** Requests no provider could ever satisfy are rejected with a reason instead of waiting forever.
- **90-day simulation** that measures wait times by urgency and compares aging strategies.

## Running it

Requires Java 17+. No libraries are needed except JUnit for the tests.

```bash
javac -d out src/main/java/*.java

# Web app: open http://localhost:8080
java -cp out WebApp            # optional: java -cp out WebApp <port>

# Simulation
java -cp out Simulate          # optional: java -cp out Simulate <seed>

# Tests (Maven downloads JUnit 5 automatically)
mvn test
```

## Web app

Two plain HTML pages with no JavaScript, served by the JDK's built-in `HttpServer`.

**Hospital page** (`/hospital`)
- Set opening and closing times for each day of the week, with a checkbox for open/closed. Patients can't book until hours are saved.
- Two-week calendar of every appointment. Click one to see the patient, time, severity, reason for visit, and how it was scheduled.
- The waitlist queue in priority order, plus any offers waiting for a patient's reply.

**Patient page** (`/user`)
- One username/password form. A new username creates an account. An existing username with the right password logs in; with the wrong password it says "Username taken."
- Booking form: date, start time, duration, severity (defaults to 1 - Low), and reason for visit.
- If the time is open, it's booked immediately and shows on both calendars. Nobody else can book it.
- If not, the patient chooses: **join the waitlist** (and see their position), or **book one of the 8 open times closest** to what they asked for.
- Click any "open" cell in the calendar to fill in the form.
- Delete an appointment, or leave the waitlist.
- When someone cancels, the freed time is offered to the first waitlisted patient it fits. They see "An opening came up for you" and have 2 hours to accept or decline. Declining keeps their place in line.

**Design notes**
- **One request at a time.** `Scheduler` isn't thread-safe, so every request runs inside `synchronized`.
- **Passwords** are hashed with PBKDF2 and a random per-user salt, and compared in constant time. The plain password is never stored.
- **Sessions** use a random cookie (`HttpOnly`, `SameSite=Lax`), and a fresh session ID is issued on login.
- **All patient text is HTML-escaped**, so a reason like `<script>` shows as text instead of running.
- **Post-redirect-get:** every form submits and then redirects, so refreshing never books twice.
- **Time mapping:** scheduler minute 0 is Monday 00:00 of the week the server started, in local time.
- **Limitations:** data is in memory only (restarting clears it), the hospital page has no login, and each patient can hold one appointment or waitlist spot at a time.

## Project structure

```
src/main/java/Scheduler.java      All scheduling logic, heavily commented
src/main/java/WebApp.java         Hospital and patient web pages
src/main/java/Simulate.java       90-day clinic simulation and reports
src/test/java/SchedulerTest.java  22 JUnit tests, including invariant checks over simulated months
```

## How it works

### Time and blocks
Time is an `int` count of minutes since the start (minute 0 = Monday 00:00), which keeps arithmetic trivial. A provider's schedule is a map from **15-minute block number → appointment ID**. A 45-minute appointment owns 3 blocks.

### No double-booking
`claim()` is the only method that takes blocks. It verifies that every block is free before taking any, and throws `IllegalStateException` otherwise. Since there's one entry point that checks everything at once, overlapping appointments can't be created. The tests also verify this property across entire simulated months.

### Waitlist priority, and why the heap never needs re-sorting
The priority we want changes over time:

```
priority(now) = URGENCY_WEIGHT × urgency + agingRate × (now − createdAt)
```

A heap's order is fixed when items are inserted, so that seems like a problem. But the `agingRate × now` term is identical for every patient, so it never changes who is ahead of whom. The ordering depends only on

```
URGENCY_WEIGHT × urgency − agingRate × createdAt
```

which never changes. That value is stored once as the heap key (negated, because Java's `PriorityQueue` is a min-heap). A test checks that the stored key always agrees with the full formula.

Ties are broken by each patient's original place in line, which is kept even if they're skipped or an offer expires.

### Lazy deletion
When a patient leaves the waitlist, their status is set to `REMOVED` instead of searching the heap for them (O(n)). Stale entries are discarded when they reach the top.

### Cancellation fill
1. Release the cancelled appointment's blocks.
2. Pop waitlisted patients in priority order until one fits the freed time (specialty, duration, allowed day, acceptable wait). Patients who don't fit are set aside and pushed back afterward.
3. Create a **hold** for that patient that expires in 2 hours.
4. If time is left over in the gap, offer the remainder to the next patient who fits.

`tick(now)` expires overdue holds, puts those patients back in line (in their original position), and offers the slot to someone else.

### Waitlist retries
As each day passes, a new day enters every patient's acceptable window, so `retryWaitlist()` runs daily and tries to book waitlisted patients in priority order.

## Simulation results

Three providers (two family medicine, one cardiology), 24–36 requests per day (slightly more than the clinic can handle), random cancellations, and patients who accept about 70% of offers. Output for seed 42:

```
Urgency  Requests   Booked    Patient    Rejected   Avg days to   Max days to     Still
                            cancelled (impossible)   appointment   appointment   waiting
5             225      205         11           1           2.8          13.3         8
4             325      274         17           1           3.7          24.8        33
3             528      394         68           4           6.9          38.0        62
2             807      680        121           6           8.7          20.0         0
1             834      693        136           5           9.0          20.5         0
Cancelled time refilled from the waitlist: 32%
```

Aging comparison on identical traffic, averaged over 10 seeds:

```
Setting                 Urgency 5 avg days   Urgency 4-5 worst wait   Urgency 1-3 worst wait
No aging                       2.6                  22.5                     29.4
Gentle (1 level/week)          2.7                  17.0                     28.8
Strong (1 level/day)           3.4                  16.9                     27.6
```

## What the simulation taught me

The simulation found problems that unit tests alone would not have:

1. **Urgent patients were being crowded out.** Routine patients can book up to 30 days ahead, so they filled the calendar before urgent patients (who need a slot within 2 days) arrived. Fix: reserve late-afternoon time for urgent patients and release it to everyone within 24 hours.
2. **The urgent reserve itself got used up early.** Urgency-4 patients (7-day window) booked the reserved time days in advance. Fix: urgent patients can only book reserved time at most 2 days ahead.
3. **Some patients waited forever** because they asked for something impossible, like cardiology on a day the cardiologist doesn't work. Fix: detect impossible requests up front and reject them with a reason.
4. **Aging is a tradeoff, not a free win.** Gentle aging cut the worst urgent wait from 22.5 to 17 days at almost no cost to the most urgent patients. Strong aging delayed the most urgent patients (2.6 → 3.4 days on average) with little extra benefit.

## Known limitation and next step

**Medium-urgency patients get squeezed.** Urgency-3 patients need a slot within 14 days, but routine patients have already booked that time weeks earlier, and the urgent reserve doesn't cover them. In the run above, 62 urgency-3 patients were still waiting at the end, while no routine patients were.

Possible fixes to try:
- Prevent routine patients from booking inside the next N days, keeping near-term time for higher urgency.
- Tiered reserves (some time for urgency 4–5, some for 3+).
- Let patients book a later slot *and* stay on the waitlist for an earlier one; accepting an earlier offer releases the later booking, which can cascade to the next patient.

## Design decisions and tradeoffs

- **In-memory, single-threaded.** Simple and fully testable. With a database, `claim()` would become a unique constraint on `(provider, block)` inside a transaction, so the database enforces the rule even with concurrent requests.
- **Greedy assignment.** Each request takes the best slot available right now. A batch optimizer (e.g. min-cost matching) could place patients better overall, at the cost of not answering immediately.
- **Simple slot scan.** Finding slots scans each day's blocks, O(days × blocks per day). That's small for one clinic; a sorted structure of free intervals would scale better.
- **Full waitlist scan on cancellation.** Filling a gap may pop many patients who don't fit. Indexing the waitlist by specialty and duration would avoid that at scale.
- **Weekends and holidays** are handled only through provider hours; there is no holiday calendar.
