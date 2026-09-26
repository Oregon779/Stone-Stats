package dev.stonestats.plugin.manager;

import dev.stonestats.plugin.StoneStats;
import dev.stonestats.plugin.config.ConfigUpdater;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads languages/&lt;lang&gt;/messages.yml, keeps every bundled language
 * cached, and renders raw strings to Adventure components. Three color
 * formats are supported at the same time with no toggle required: legacy
 * &amp;-codes, &amp;#RRGGBB hex, and MiniMessage tags such as
 * &lt;gradient&gt;.
 */
public class MessageManager {

    private static final Map<Character, String> LEGACY_TAGS = new HashMap<>();
    private static final String[] BUNDLED_LANGUAGES = {"en", "de"};
    // Matches {stat_kills}, {player}, etc. in one pass. Compiled once and
    // reused - Pattern compilation itself is non-trivial work we don't
    // want repeated on every single render.
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}");

    static {
        LEGACY_TAGS.put('0', "black");
        LEGACY_TAGS.put('1', "dark_blue");
        LEGACY_TAGS.put('2', "dark_green");
        LEGACY_TAGS.put('3', "dark_aqua");
        LEGACY_TAGS.put('4', "dark_red");
        LEGACY_TAGS.put('5', "dark_purple");
        LEGACY_TAGS.put('6', "gold");
        LEGACY_TAGS.put('7', "gray");
        LEGACY_TAGS.put('8', "dark_gray");
        LEGACY_TAGS.put('9', "blue");
        LEGACY_TAGS.put('a', "green");
        LEGACY_TAGS.put('b', "aqua");
        LEGACY_TAGS.put('c', "red");
        LEGACY_TAGS.put('d', "light_purple");
        LEGACY_TAGS.put('e', "yellow");
        LEGACY_TAGS.put('f', "white");
        LEGACY_TAGS.put('k', "obfuscated");
        LEGACY_TAGS.put('l', "bold");
        LEGACY_TAGS.put('m', "strikethrough");
        LEGACY_TAGS.put('n', "underlined");
        LEGACY_TAGS.put('o', "italic");
        LEGACY_TAGS.put('r', "reset");
    }

    private final StoneStats plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, YamlConfiguration> languageCache = new HashMap<>();
    private String activeLanguage = "en";

    public MessageManager(StoneStats plugin) {
        this.plugin = plugin;
    }

    public void load() {
        languageCache.clear();
        activeLanguage = plugin.getConfigManager().getLanguage();

        for (String lang : BUNDLED_LANGUAGES) {
            loadLanguage(lang);
        }
        if (!languageCache.containsKey(activeLanguage)) {
            loadLanguage(activeLanguage);
        }
    }

    private void loadLanguage(String lang) {
        String resourcePath = "languages/" + lang + "/messages.yml";
        File langFolder = new File(plugin.getDataFolder(), "languages/" + lang);
        File file = new File(langFolder, "messages.yml");

        if (!file.exists()) {
            langFolder.mkdirs();
            try (InputStream in = plugin.getResource(resourcePath)) {
                if (in == null) {
                    plugin.getLogger().warning("No bundled messages.yml found for language '" + lang + "'");
                    return;
                }
                Files.copy(in, file.toPath());
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not create messages.yml for '" + lang + "': " + ex.getMessage());
                return;
            }
        }

        try {
            ConfigUpdater.UpdateResult result = ConfigUpdater.update(plugin, resourcePath, file);
            if (result.addedKeys() > 0) {
                plugin.getLogger().info("Added " + result.addedKeys() + " new message key(s) to languages/" + lang + "/messages.yml");
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to update messages for '" + lang + "': " + ex.getMessage());
        }

        languageCache.put(lang, ConfigUpdater.loadOrDefaults(plugin, resourcePath, file));
    }

    public String getRaw(String path) {
        YamlConfiguration active = languageCache.get(activeLanguage);
        String value = active != null ? active.getString(path) : null;
        if (value == null) {
            YamlConfiguration fallback = languageCache.get("en");
            value = fallback != null ? fallback.getString(path) : null;
        }
        return value != null ? value : "";
    }

    /**
     * PERFORMANCE: opening the stats GUI renders the title plus every
     * item's name and lore lines (title + up to ~10 items x ~2-5 lines =
     * dozens of template strings per single /stats call, executed
     * synchronously on the main thread). The old implementation called
     * String#replace() once per placeholder in the map (currently ~10 and
     * growing with every new stat), so a single short lore line was
     * rescanned end-to-end up to 10 times even though it contains at most
     * one or two actual placeholders. With 250 players able to spam
     * /stats, that redundant scanning is pure wasted main-thread CPU.
     * <p>
     * This does ONE linear pass over {@code raw} with a precompiled regex,
     * substituting only the placeholders that are actually present -
     * O(length of raw) instead of O(placeholders_in_map x length of raw).
     */
    private String applyPlaceholders(String raw, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty() || raw.indexOf('{') < 0) {
            // Guard clause: most short static lines (dividers, headers,
            // command feedback without dynamic content) contain no '{' at
            // all - skip building a Matcher for those entirely.
            return raw;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return raw;
        }

        StringBuilder result = new StringBuilder(raw.length() + 16);
        int lastEnd = 0;
        do {
            String value = placeholders.get(matcher.group(1));
            result.append(raw, lastEnd, matcher.start());
            result.append(value != null ? value : matcher.group());
            lastEnd = matcher.end();
        } while (matcher.find());
        result.append(raw, lastEnd, raw.length());
        return result.toString();
    }

    private String convertLegacyToMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int len = input.length();
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);
            if ((c == '&' || c == '\u00a7') && i + 1 < len) {
                char next = input.charAt(i + 1);
                if (next == '#' && i + 8 <= len && isHex(safeSub(input, i + 2, i + 8))) {
                    String hex = input.substring(i + 2, i + 8);
                    sb.append("<#").append(hex).append('>');
                    i += 7;
                    continue;
                }
                char lower = Character.toLowerCase(next);
                if (LEGACY_TAGS.containsKey(lower)) {
                    sb.append('<').append(LEGACY_TAGS.get(lower)).append('>');
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private String safeSub(String input, int start, int end) {
        if (start < 0 || end > input.length() || start > end) {
            return "";
        }
        return input.substring(start, end);
    }

    private boolean isHex(String s) {
        if (s.length() != 6) {
            return false;
        }
        for (char c : s.toCharArray()) {
            if (Character.digit(c, 16) == -1) {
                return false;
            }
        }
        return true;
    }

    public Component format(String raw, Map<String, String> placeholders) {
        return format(raw, placeholders, null);
    }

    public Component format(String raw, Map<String, String> placeholders, OfflinePlayer papiTarget) {
        String withPlaceholders = applyPlaceholders(raw, placeholders);
        withPlaceholders = plugin.getPlaceholderApiHook().apply(papiTarget, withPlaceholders);
        String miniMessageReady = convertLegacyToMiniMessage(withPlaceholders);
        return miniMessage.deserialize(miniMessageReady);
    }

    /**
     * PERFORMANCE: converts &amp;-codes/hex to MiniMessage tags once, ahead
     * of time, for text that gets re-rendered very often with different
     * placeholder values (e.g. every stats-GUI item name/lore, once per
     * {@code /stats} open). The char-by-char scan in
     * {@link #convertLegacyToMiniMessage(String)} is the expensive part of
     * formatting a string; doing it once at config-load time instead of on
     * every single render is what actually matters once a few hundred
     * players are opening the GUI regularly. Pair with
     * {@link #renderPrecompiled(String, Map)} at render time.
     */
    public String precompile(String raw) {
        return convertLegacyToMiniMessage(raw);
    }

    /**
     * Renders a template already produced by {@link #precompile(String)}:
     * placeholder substitution + MiniMessage parse, no repeated legacy-
     * color scanning. This overload skips PlaceholderAPI (no player
     * context available) - use {@link #renderPrecompiled(String, Map, OfflinePlayer)}
     * wherever a target player is known.
     */
    public Component renderPrecompiled(String miniMessageTemplate, Map<String, String> placeholders) {
        return renderPrecompiled(miniMessageTemplate, placeholders, null);
    }

    /**
     * Same as above, but also resolves any %placeholder% from
     * PlaceholderAPI (if installed) for {@code papiTarget} - lets admins
     * mix Stone Stats' own {stat_x} tokens and PlaceholderAPI's %x%
     * syntax freely in the same config.yml line.
     */
    public Component renderPrecompiled(String miniMessageTemplate, Map<String, String> placeholders, OfflinePlayer papiTarget) {
        String withPlaceholders = applyPlaceholders(miniMessageTemplate, placeholders);
        withPlaceholders = plugin.getPlaceholderApiHook().apply(papiTarget, withPlaceholders);
        return miniMessage.deserialize(withPlaceholders);
    }

    public String getFormattedRaw(String path, Map<String, String> placeholders) {
        return applyPlaceholders(getRaw(path), placeholders);
    }

    public void sendChat(CommandSender target, String path, Map<String, String> placeholders) {
        String prefixed = getRaw("prefix") + getRaw(path);
        OfflinePlayer papiTarget = target instanceof OfflinePlayer offline ? offline : null;
        target.sendMessage(format(prefixed, placeholders, papiTarget));
    }

    public void sendRaw(CommandSender target, String path, Map<String, String> placeholders) {
        OfflinePlayer papiTarget = target instanceof OfflinePlayer offline ? offline : null;
        target.sendMessage(format(getRaw(path), placeholders, papiTarget));
    }
}
