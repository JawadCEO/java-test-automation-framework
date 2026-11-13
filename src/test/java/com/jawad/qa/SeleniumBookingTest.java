package com.jawad.qa;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("selenium")
class SeleniumBookingTest {
    @Test
    void booksSeatInChromium() throws Exception {
        try (var app = new BookingApp()) {
            var options = new ChromeOptions().addArguments("--headless=new");
            String binary = System.getProperty("selenium.chrome.binary");
            if (binary != null) {
                options.setBinary(binary);
            }
            WebDriver driver = new ChromeDriver(options);
            try {
                driver.get(app.url());
                var page = new BookingPage(driver);
                page.book("Ada");
                assertTrue(page.confirms("Ada"));
            } finally {
                driver.quit();
            }
        }
    }

    private record BookingPage(WebDriver driver) {
        void book(String name) {
            driver.findElement(By.id("name")).sendKeys(name);
            driver.findElement(By.cssSelector("button[type=submit]")).click();
        }

        boolean confirms(String name) {
            return new WebDriverWait(driver, Duration.ofSeconds(5)).until(
                ExpectedConditions.textToBe(By.cssSelector("[role=status]"), "Booked: " + name));
        }
    }
}
