A hospital scheduling engine in Java. It assigns patients to appointment slots based on provider availability, appointment length, urgency, and patient preferences. I used a priority-queue for the waitlist and it automatically offers cancelled time to the next patient who fits.

Requires Java 17+. No libraries unless JUnit for the tests.

```bash
javac -d out src/main/java/*.java

open http://localhost:8080
java -cp out WebApp

if you want to run the simulation:
java -cp out Simulate

tests: mvn test
```
