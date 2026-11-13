# Java Test Automation Framework

Phase 1 exercises a local booking fixture through REST Assured, Cucumber, and Selenium. The fixture binds to a dynamic loopback port, so runs use isolated test data.

Requires JDK 21 and Maven 3.9. The Selenium suite also requires Chrome or Chromium and a compatible ChromeDriver.

```bash
mvn clean test
mvn test -Pselenium
```

Use `-Dselenium.chrome.binary=/path/to/chromium` for a custom browser installation.

The suite structure was inspired by [PirashanthJ/java-test-automation-framework](https://github.com/PirashanthJ/java-test-automation-framework).
