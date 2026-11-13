package com.jawad.qa.unit;

import com.jawad.booking.BookingRules;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class BookingRulesTest {
    @ParameterizedTest(name = "[{index}] \"{0}\" is stored as \"{1}\"")
    @CsvSource(delimiter = '|', value = {
        "Ada|Ada",
        "'  Ada  '|Ada",
        "O'Neil|O'Neil",
        "José Álvarez|José Álvarez",
        "李小龍|李小龍",
        "a|a",
    })
    void acceptsAndNormalizesNames(String raw, String stored) {
        assertEquals(Optional.of(stored), BookingRules.normalizeName(raw));
    }

    @Test
    void acceptsNameAtTheLengthLimit() {
        String limit = "x".repeat(BookingRules.MAX_NAME_LENGTH);
        assertEquals(Optional.of(limit), BookingRules.normalizeName(limit));
    }

    @ParameterizedTest(name = "[{index}] rejects \"{0}\"")
    @NullSource
    @ValueSource(strings = {"", " ", "\t", "\n  \n", "Ada\u0000Lovelace", "line\nbreak"})
    void rejectsBlankOrControlCharacterNames(String raw) {
        assertTrue(BookingRules.normalizeName(raw).isEmpty());
    }

    @Test
    void rejectsNameOneOverTheLimit() {
        assertTrue(BookingRules.normalizeName("x".repeat(BookingRules.MAX_NAME_LENGTH + 1)).isEmpty());
    }

    @Test
    void parsesCanonicalUuid() {
        UUID id = UUID.randomUUID();
        assertEquals(Optional.of(id), BookingRules.parseId(id.toString()));
        assertEquals(Optional.of(id), BookingRules.parseId(id.toString().toUpperCase()));
    }

    @ParameterizedTest(name = "[{index}] rejects id \"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "invalid", "1-1-1-1-1", "00000000-0000-0000-0000-00000000000", "../etc/passwd",
        "00000000-0000-0000-0000-0000000000001"})
    void rejectsNonCanonicalIds(String raw) {
        assertTrue(BookingRules.parseId(raw).isEmpty());
    }

    @ParameterizedTest(name = "[{index}] limit \"{0}\" becomes {1}")
    @CsvSource(nullValues = "NULL", value = {"NULL,20", "'',20", "5,5", "0,1", "-3,1", "100,100", "101,100",
        "' 7 ',7", "abc,-1", "1.5,-1"})
    void clampsPageSize(String raw, int expected) {
        assertEquals(expected, BookingRules.pageSize(raw, 20));
    }
}
