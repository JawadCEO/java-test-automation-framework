package com.jawad.booking;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Pure validation rules shared by the HTTP layer and covered by the unit layer. */
public final class BookingRules {
    public static final int MAX_NAME_LENGTH = 80;
    public static final int MAX_BODY_BYTES = 4096;
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");
    private static final Pattern CANONICAL_UUID =
        Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private BookingRules() {
    }

    /** Returns the stored form of an attendee name, or empty when the name is not acceptable. */
    public static Optional<String> normalizeName(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String name = raw.strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || CONTROL_CHARACTERS.matcher(name).find()) {
            return Optional.empty();
        }
        return Optional.of(name);
    }

    /** Accepts only the canonical 36-character UUID form; {@link UUID#fromString} alone is more lenient. */
    public static Optional<UUID> parseId(String raw) {
        if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(raw));
    }

    /** Clamps a requested page size to the range the API supports. */
    public static int pageSize(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            return Math.max(1, Math.min(value, 100));
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }
}
