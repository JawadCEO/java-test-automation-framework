package com.jawad.qa;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import io.qameta.allure.Allure;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

@Tag("playwright")
class PlaywrightBookingTest {
    @Test
    void attendeeCanBookASeat() throws Exception {
        try (var app = new BookingApp(); var playwright = Playwright.create()) {
            String engine = System.getProperty("browser", "chromium");
            BrowserType browserType = switch (engine) {
                case "chromium" -> playwright.chromium();
                case "firefox" -> playwright.firefox();
                default -> throw new IllegalArgumentException("Unsupported browser: " + engine);
            };
            var options = new BrowserType.LaunchOptions().setHeadless(true);
            String executable = System.getProperty("playwright.executable");
            if (executable != null) {
                options.setExecutablePath(Path.of(executable));
            }
            try (var browser = browserType.launch(options); var context = browser.newContext()) {
                context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
                var page = context.newPage();
                try {
                    page.navigate(app.url());
                    new BookingPage(page).book("Ada");
                    assertThat(page.getByRole(AriaRole.STATUS)).hasText("Booked: Ada");
                } catch (AssertionError | RuntimeException failure) {
                    try {
                        saveEvidence(context, page);
                    } catch (Exception evidenceFailure) {
                        failure.addSuppressed(evidenceFailure);
                    }
                    throw failure;
                }
            }
        }
    }

    private void saveEvidence(BrowserContext context, Page page) throws Exception {
        Path directory = Files.createDirectories(Path.of("target", "playwright-evidence"));
        Path trace = Files.createTempFile(directory, "trace-", ".zip");
        context.tracing().stop(new Tracing.StopOptions().setPath(trace));
        try (var input = Files.newInputStream(trace)) {
            Allure.addAttachment("Playwright trace", "application/zip", input, ".zip");
        }
        byte[] screenshot = page.screenshot();
        Files.write(Files.createTempFile(directory, "booking-", ".png"), screenshot);
        Allure.addAttachment("Booking page", "image/png", new ByteArrayInputStream(screenshot), ".png");
    }

    private record BookingPage(Page page) {
        void book(String name) {
            page.getByLabel("Attendee name").fill(name);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Book seat")).click();
        }
    }
}
