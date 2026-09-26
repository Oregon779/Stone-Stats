package dev.stonestats.plugin.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Merges newly added keys from the bundled default resource into an existing
 * file on disk without ever overwriting values the user already changed -
 * including inside nested sections that were restructured between versions.
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    public static UpdateResult update(JavaPlugin plugin, String resourcePath, File targetFile) throws IOException {
        return update(plugin, resourcePath, targetFile, Set.of());
    }

    /**
     * @param userOwnedSections paths whose entries belong to the user (e.g. the
     *                          GUI item list): added whole when missing, but never
     *                          refilled - otherwise an item the user deleted would
     *                          come back on every restart.
     */
    public static UpdateResult update(JavaPlugin plugin, String resourcePath, File targetFile,
                                      Set<String> userOwnedSections) throws IOException {
        YamlConfiguration currentConfig = new YamlConfiguration();
        try {
            currentConfig.load(targetFile);
        } catch (InvalidConfigurationException ex) {
            // loadConfiguration() would return an empty config here, and the
            // merge below would then overwrite the user's file with defaults.
            throw new IOException(targetFile.getName() + " has a YAML error, leaving it untouched", ex);
        }

        try (InputStream defaultStream = plugin.getResource(resourcePath)) {
            if (defaultStream == null) {
                return new UpdateResult(false, 0);
            }

            YamlConfiguration defaultConfig;
            try (Reader reader = new InputStreamReader(defaultStream, StandardCharsets.UTF_8)) {
                defaultConfig = YamlConfiguration.loadConfiguration(reader);
            }

            int added = mergeSection(defaultConfig, currentConfig, userOwnedSections);
            if (added > 0) {
                currentConfig.save(targetFile);
            }
            return new UpdateResult(added > 0, added);
        }
    }

    /**
     * Loads {@code file}; if it can't be parsed, logs why and returns the
     * bundled defaults instead, so a typo never leaves the plugin without a
     * working configuration. The broken file itself is left as it is.
     */
    public static YamlConfiguration loadOrDefaults(JavaPlugin plugin, String resourcePath, File file) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
            return config;
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().severe(file.getName() + " can't be read (" + ex.getMessage()
                    + ") - using the built-in defaults until it's fixed. Your file was not changed.");
        }
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    defaults.load(reader);
                }
            }
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().severe("Could not load built-in " + resourcePath + ": " + ex.getMessage());
        }
        return defaults;
    }

    private static int mergeSection(ConfigurationSection defaults, ConfigurationSection current, Set<String> userOwnedSections) {
        int added = 0;
        for (String key : defaults.getKeys(false)) {
            Object defaultValue = defaults.get(key);

            if (!current.contains(key, true)) {
                current.set(key, defaultValue);
                added++;
                continue;
            }

            if (defaultValue instanceof ConfigurationSection defaultSection && current.isConfigurationSection(key)
                    && !userOwnedSections.contains(defaultSection.getCurrentPath())) {
                added += mergeSection(defaultSection, current.getConfigurationSection(key), userOwnedSections);
            }
        }
        return added;
    }

    public record UpdateResult(boolean changed, int addedKeys) {
    }
}
