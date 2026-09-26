package dev.stonestats.plugin.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextUtilTest {

    @Test
    void acceptsJavaAndBedrockNames() {
        assertTrue(TextUtil.isValidPlayerName("Steve"));
        assertTrue(TextUtil.isValidPlayerName("a_b_C_123"));
        assertTrue(TextUtil.isValidPlayerName(".BedrockUser"));
        assertTrue(TextUtil.isValidPlayerName("1234567890123456"));
    }

    @Test
    void rejectsAnythingThatCannotBeAName() {
        assertFalse(TextUtil.isValidPlayerName(""));
        assertFalse(TextUtil.isValidPlayerName("12345678901234567"));
        assertFalse(TextUtil.isValidPlayerName("%player_ip%"));
        assertFalse(TextUtil.isValidPlayerName("<red>x"));
        assertFalse(TextUtil.isValidPlayerName("&cName"));
        assertFalse(TextUtil.isValidPlayerName("{player}"));
        assertFalse(TextUtil.isValidPlayerName("Stève"));
    }

    @Test
    void safeInputStripsDangerousCharactersAndCapsLength() {
        assertEquals("player_ip", TextUtil.safeInput("%player_ip%"));
        assertEquals("parseother_Admin", TextUtil.safeInput("%parseother_{Admin}_{player_ip}%"));
        assertEquals("redx", TextUtil.safeInput("<red>x"));
        assertEquals(16, TextUtil.safeInput("abcdefghijklmnopqrstuvwxyz").length());
    }
}
