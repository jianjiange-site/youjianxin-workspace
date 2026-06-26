package com.dating.im.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ContactInfoDetector} — anti-funnel regex coverage (positives + negatives).
 */
class ContactInfoDetectorTest {

    private final ContactInfoDetector detector = new ContactInfoDetector();

    // --- US phone numbers (positive) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "call me 234-567-8901",
            "here is my number (234) 567-8901 ok?",
            "234.567.8901",
            "reach me at 2345678901",
            "+1 234 567 8901",
            "+12345678901",
            "1-234-567-8901",
    })
    void detectsUsPhone(String content) {
        assertEquals("us_phone", detector.detect(content));
    }

    // --- social handles / urls (positive) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "follow me instagram.com/john.doe",
            "my insta is johnd",
            "add me on IG: johnd",
            "ins @johnd",
    })
    void detectsInstagram(String content) {
        assertEquals("instagram", detector.detect(content));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "facebook.com/john.doe",
            "add me on facebook",
            "fb: johnd",
            "m.me/johndoe",
    })
    void detectsFacebook(String content) {
        assertEquals("facebook", detector.detect(content));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "whatsapp me",
            "whats app please",
            "wa.me/12345678901",
    })
    void detectsWhatsapp(String content) {
        assertEquals("whatsapp", detector.detect(content));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "telegram @johnd",
            "t.me/johnd",
            "tg: @johnd",
    })
    void detectsTelegram(String content) {
        assertEquals("telegram", detector.detect(content));
    }

    // --- negatives (must NOT trip) ---

    @ParameterizedTest
    @ValueSource(strings = {
            "hello how are you today",
            "let's meet tomorrow at 5pm",
            "please ignore my last message",
            "this is insane and instrumental",
            "order id 100200300400500",
            "the year was 2020 and then 1999",
            "the fbi is watching",
            "john@example.com",
            "see you at 7",
    })
    void allowsCleanContent(String content) {
        assertNull(detector.detect(content));
    }

    @Test
    void allowsNullAndEmpty() {
        assertNull(detector.detect(null));
        assertNull(detector.detect(""));
    }

    @Test
    void caseInsensitive() {
        assertEquals("instagram", detector.detect("Follow My INSTAGRAM Now"));
    }
}
