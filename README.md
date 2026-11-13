# Java Test Automation Framework

A QA framework that exercises seat booking through the browser, HTTP API, and database. The demo service runs on a dynamic loopback port and gives each test its own fixture.

| Phase | Coverage |
| --- | --- |
| `phase-1` | Java 21, JUnit 5, Maven, REST Assured, Cucumber, Selenium, and an in-memory booking fixture |
| `phase-2` | Playwright Chromium and Firefox, Allure, GitHub Actions, JDBC validation with H2, k6, and operational checks |

## Run locally

Install JDK 21 and Maven 3.9. Browser suites and the load gate have separate prerequisites.

```bash
mvn clean test
mvn -Dexec.classpathScope=test -Dexec.mainClass=com.microsoft.playwright.CLI \
  '-Dexec.args=install chromium firefox' exec:java
mvn test -Pplaywright
mvn test -Pfirefox
mvn allure:report
```

For Playwright on Linux, add `--with-deps` to the install arguments if system libraries are missing. Use `-Dplaywright.executable=/path/to/chromium` with the Chromium profile to try an existing browser. Raw Allure results go to `target/allure-results` and the HTML report to `target/site/allure-maven-plugin`. Use `mvn allure:serve` to view it. Run `mvn clean` before a suite when you want a report for that run alone. Failed Playwright assertions attach a screenshot and trace when capture succeeds. Open a trace with the Playwright CLI `show-trace` command.

The optional Selenium regression runs with `mvn test -Pselenium`. It requires Chrome or Chromium and a compatible ChromeDriver. Use `-Dselenium.chrome.binary=/path/to/chromium` when necessary.

## Database, load, and operational checks

- **Database** exercises the API and then queries H2 through a separate JDBC connection. It checks that the committed booking has the expected UUID and normalized name and that a rejected request leaves no extra row.
- **Performance** uses k6 and Bash. Run `./performance/run.sh` with k6 on PATH. The default workload starts 20 booking iterations per second for 20 seconds. Each iteration creates and reads back a booking. The gate requires p95 under 250 ms, HTTP failures under 1%, all business checks passing, and zero dropped iterations. The JSON summary is written to `target/k6-summary.json`. Override the workload with `BOOKINGS_PER_SECOND` and `LOAD_DURATION`. Treat these thresholds as local regression budgets for the fixture.
- **Observability and resilience** cover database-backed readiness, a simulated database failure, unique response request IDs, and Prometheus-format request, error, and duration counters. Request logs carry the request ID, method, response status, and duration. Start the fixture with `mvn test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.jawad.qa.FixtureMain` to inspect `/health` and `/metrics` at the printed address.

## CI and release

GitHub Actions runs the integration, browser matrix, and performance jobs and uploads test evidence. Pushing a `v*` tag triggers delivery of a source ZIP through GitHub Releases after those jobs pass. The booking service is a test fixture.

## References

- [PirashanthJ/java-test-automation-framework](https://github.com/PirashanthJ/java-test-automation-framework) informed the Java UI, API, and BDD structure.
- [SarkarPranab/Playwright-Allure-CI-CD-Framework](https://github.com/SarkarPranab/Playwright-Allure-CI-CD-Framework) informed the Playwright page objects, browser profiles, Allure, and CI workflow. This project retains JUnit 5 across phases.
