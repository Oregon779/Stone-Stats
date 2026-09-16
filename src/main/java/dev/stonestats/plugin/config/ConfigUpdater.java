package dev.stonestats.plugin.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Merges newly added keys from the bundled default resource into an existing
 * file on disk without ever overwriting values the user already changed -
 * including inside nested sections that were restructured between versions.
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    public static UpdateResult update(JavaPlugin plugin, String resourcePath, File targetFile) throws IOException {
        YamlConfiguration currentConfig = YamlConfiguration.loadConfiguration(targetFile);

        try (InputStream defaultStream = plugin.getResource(resourcePath)) {
            if (defaultStream == null) {
                return new UpdateResult(false, 0);
            }

            YamlConfiguration defaultConfig;
            try (Reader reader = new InputStreamReader(defaultStream, StandardCharsets.UTF_8)) {
                defaultConfig = YamlConfiguration.loadConfiguration(reader);
            }

            int added = mergeSection(defaultConfig, currentConfig);
            if (added > 0) {
                currentConfig.save(targetFile);
            }
            return new UpdateResult(added > 0, added);
        }
    }

    private static int mergeSection(ConfigurationSection defaults, ConfigurationSection current) {
        int added = 0;
        for (String key : defaults.getKeys(false)) {
            Object defaultValue = defaults.get(key);

            if (!current.contains(key, true)) {
                current.set(key, defaultValue);
                added++;
                continue;
            }

            if (defaultValue instanceof ConfigurationSection defaultSection && current.isConfigurationSection(key)) {
                added += mergeSection(defaultSection, current.getConfigurationSection(key));
            }
        }
        return added;
    }

    public record UpdateResult(boolean changed, int addedKeys) {
    }
}
