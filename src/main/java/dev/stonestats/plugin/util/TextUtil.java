package dev.stonestats.plugin.util;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Formatting helpers for text that can't be authored ahead of time in
 * config.yml because it depends on live server data - e.g. an equipped
 * item's material name, a durability/XP progress bar, or a player's name
 * converted to match the GUI's small-caps title style. Everything that
 * CAN be pre-written (static headers, labels) lives directly as Unicode
 * small caps + MiniMessage gradient tags in config.yml instead, so this
 * class only covers the genuinely dynamic cases.
 */
public final class TextUtil {

    // PERFORMANCE: plain char[26] array instead of a HashMap<Character,
    // Character>. toSmallCaps() runs on every /stats open (it builds the
    // {player_sc} placeholder used in the GUI title), so with 250+ players
    // able to open the GUI concurrently, every character of every player
    // name was paying HashMap overhead (hashCode() + bucket lookup, plus
    // the Character autoboxing on both the key and the returned value)
    // for what is fundamentally a fixed a-z lookup table. A direct array
    // index (c - 'a') is a single memory read with no hashing, no boxing,
    // and no branch beyond the a-z range check already needed either way.
    private static final char[] SMALL_CAPS = new char[26];

    static {
        // Identity by default - characters with no small-caps glyph (see
        // note below) simply pass through unchanged.
        for (char c = 'a'; c <= 'z'; c++) {
            SMALL_CAPS[c - 'a'] = c;
        }
        SMALL_CAPS['a' - 'a'] = '\u1D00';
        SMALL_CAPS['b' - 'a'] = '\u0299';
        SMALL_CAPS['c' - 'a'] = '\u1D04';
        SMALL_CAPS['d' - 'a'] = '\u1D05';
        SMALL_CAPS['e' - 'a'] = '\u1D07';
        SMALL_CAPS['f' - 'a'] = '\uA730';
        SMALL_CAPS['g' - 'a'] = '\u0262';
        SMALL_CAPS['h' - 'a'] = '\u029C';
        SMALL_CAPS['i' - 'a'] = '\u026A';
        SMALL_CAPS['j' - 'a'] = '\u1D0A';
        SMALL_CAPS['k' - 'a'] = '\u1D0B';
        SMALL_CAPS['l' - 'a'] = '\u029F';
        SMALL_CAPS['m' - 'a'] = '\u1D0D';
        SMALL_CAPS['n' - 'a'] = '\u0274';
        SMALL_CAPS['o' - 'a'] = '\u1D0F';
        SMALL_CAPS['p' - 'a'] = '\u1D18';
        // 'q' and 's' are deliberately left as identity (no small-caps
        // glyph renders reliably in Minecraft's bundled font).
        SMALL_CAPS['r' - 'a'] = '\u0280';
        SMALL_CAPS['t' - 'a'] = '\u1D1B';
        SMALL_CAPS['u' - 'a'] = '\u1D1C';
        SMALL_CAPS['v' - 'a'] = '\u1D20';
        SMALL_CAPS['w' - 'a'] = '\u1D21';
        // No dedicated small-caps glyph exists for x in Unicode's phonetic
        // extension blocks - y has one, x does not, so x stays identity.
        SMALL_CAPS['y' - 'a'] = '\u028F';
        SMALL_CAPS['z' - 'a'] = '\u1D22';
    }

    private TextUtil() {
    }

    /**
     * Converts arbitrary text to Unicode small caps - used for the
     * {player_sc} placeholder (GUI title). Non-letters (digits, spaces,
     * punctuation) pass through unchanged.
     */
    public static String toSmallCaps(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = Character.toLowerCase(input.charAt(i));
            sb.append(c >= 'a' && c <= 'z' ? SMALL_CAPS[c - 'a'] : input.charAt(i));
        }
        return sb.toString();
    }

    /** "NETHERITE_SWORD" -&gt; "Netherite Sword", for live equipment names. */
    public static String formatMaterialName(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    /**
     * Builds a fixed-length text progress bar, e.g. "██████░░░░" at 60%
     * with length 10. The caller wraps the result in a MiniMessage
     * &lt;gradient&gt; tag via config, so the actual color fill is handled
     * entirely by Adventure's renderer - this only produces the characters.
     */
    public static String progressBar(double ratio, int length, char filledChar, char emptyChar) {
        double clamped = Math.max(0.0, Math.min(1.0, ratio));
        int filled = (int) Math.round(clamped * length);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(i < filled ? filledChar : emptyChar);
        }
        return sb.toString();
    }

    /**
     * Keeps only characters that can appear in a player name or stat key.
     * Raw command arguments go through PlaceholderAPI and MiniMessage when
     * echoed back, so "%parseother_...%" or "&lt;click:...&gt;" must never
     * survive into a message.
     */
    public static String safeInput(String input) {
        StringBuilder sb = new StringBuilder(Math.min(input.length(), 16));
        for (int i = 0; i < input.length() && sb.length() < 16; i++) {
            char c = input.charAt(i);
            if (isNameChar(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Java names use [A-Za-z0-9_]; '.', '*' and '-' cover Bedrock prefixes (Geyser/Floodgate). */
    static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '.' || c == '*' || c == '-';
    }

    public static boolean isValidPlayerName(String name) {
        if (name.isEmpty() || name.length() > 16) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (!isNameChar(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static String formatPercent(double ratio) {
        return String.format(Locale.US, "%.0f", Math.max(0.0, Math.min(1.0, ratio)) * 100);
    }
}
