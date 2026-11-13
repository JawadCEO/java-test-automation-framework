package com.jawad.qa.ui;

import com.jawad.booking.BookingApp;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import io.qameta.allure.Allure;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** One browser per class, one context per test; a trace and screenshot are attached when a test fails. */
@Tag("playwright")
class PlaywrightBookingTest {
    private static BookingApp app;
    private static Playwright playwright;
    private static Browser browser;
    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void launch() throws Exception {
        app = new BookingApp();
        playwright = Playwright.create();
        String engine = System.getProperty("browser", "chromium");
        BrowserType browserType = switch (engine) {
            case "chromium" -> playwright.chromium();
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> throw new IllegalArgumentException("Unsupported browser: " + engine);
        };
        var options = new BrowserType.LaunchOptions().setHeadless(true);
        String executable = System.getProperty("playwright.executable");
        if (executable != null) {
            options.setExecutablePath(Path.of(executable));
        }
        browser = browserType.launch(options);
    }

    @AfterAll
    static void shutDown() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (app != null) {
            app.close();
        }
    }

    @BeforeEach
    void openPage() {
        context = browser.newContext();
        context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
        page = context.newPage();
        page.navigate(app.url());
    }

    @AfterEach
    void closePage() {
        context.close();
    }

    private void withEvidence(TestInfo info, Runnable steps) throws Exception {
        try {
            steps.run();
        } catch (AssertionError | RuntimeException failure) {
            try {
                saveEvidence(info.getDisplayName());
            } catch (Exception evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
            throw failure;
        }
    }

    @Test
    void attendeeCanBookASeat(TestInfo info) throws Exception {
        withEvidence(info, () -> {
            new BookingPage(page).book("Ada");
            assertThat(page.getByRole(AriaRole.STATUS)).hasText("Booked: Ada");
        });
    }

    @Test
    void newBookingAppearsInRecentListAndFormClears(TestInfo info) throws Exception {
        withEvidence(info, () -> {
            var booking = new BookingPage(page);
            booking.book("Grace Hopper");
            assertThat(booking.recent().getByText("Grace Hopper")).isVisible();
            assertThat(booking.nameField()).hasValue("");
        });
    }

    @Test
    void whitespaceOnlyNameShowsServerValidationMessage(TestInfo info) throws Exception {
        withEvidence(info, () -> {
            new BookingPage(page).book("   ");
            assertThat(page.getByRole(AriaRole.STATUS)).hasText("Name must contain 1 to 80 characters");
        });
    }

    @Test
    void nameFieldIsAccessibleAndLimitedTo80Characters(TestInfo info) throws Exception {
        withEvidence(info, () -> {
            var field = new BookingPage(page).nameField();
            assertThat(field).hasAttribute("maxlength", "80");
            field.fill("x".repeat(90));
            assertThat(field).hasValue("x".repeat(80));
        });
    }

    @Test
    void emptySubmitIsBlockedByTheBrowser(TestInfo info) throws Exception {
        withEvidence(info, () -> {
            int[] posts = {0};
            page.onRequest(request -> {
                if (request.method().equals("POST")) {
                    posts[0]++;
                }
            });
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Book seat")).click();
            var field = new BookingPage(page).nameField();
            if (!Boolean.TRUE.equals(field.evaluate("element => element.validity.valueMissing"))) {
                throw new AssertionError("Empty name must fail native form validation");
            }
            assertThat(page.getByRole(AriaRole.STATUS)).hasText("");
            if (posts[0] != 0) {
                throw new AssertionError("Empty form must not reach the API");
            }
        });
    }

    private void saveEvidence(String name) throws Exception {
        Path directory = Files.createDirectories(Path.of("target", "playwright-evidence"));
        Path trace = Files.createTempFile(directory, "trace-", ".zip");
        context.tracing().stop(new Tracing.StopOptions().setPath(trace));
        try (var input = Files.newInputStream(trace)) {
            Allure.addAttachment(name + " trace", "application/zip", input, ".zip");
        }
        byte[] screenshot = page.screenshot();
        Files.write(Files.createTempFile(directory, "booking-", ".png"), screenshot);
        Allure.addAttachment(name + " screenshot", "image/png", new ByteArrayInputStream(screenshot), ".png");
    }

    private record BookingPage(Page page) {
        Locator nameField() {
            return page.getByLabel("Attendee name");
        }

        Locator recent() {
            return page.getByRole(AriaRole.LIST, new Page.GetByRoleOptions().setName("Recent bookings"));
        }

        void book(String name) {
            nameField().fill(name);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Book seat")).click();
        }
    }
}
