package dev.stonestats.plugin.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.function.Consumer;

/**
 * One step that brings a config file up to {@code toVersion}. Only needed when
 * options are renamed, moved or removed - plain additions are picked up from
 * the bundled default file automatically.
 */
public record ConfigMigration(int toVersion, String description, Consumer<YamlConfiguration> action) {

    /** Moves a value (or whole section, with its comments) to a new path, keeping what the admin set. */
    public static ConfigMigration rename(int toVersion, String from, String to) {
        return new ConfigMigration(toVersion, "rename " + from + " -> " + to, config -> {
            if (!config.contains(from, true)) {
                return;
            }
            if (!config.contains(to, true)) {
                Object value = config.get(from);
                if (value instanceof ConfigurationSection section) {
                    ConfigUpdater.copySection(section, config.createSection(to));
                } else {
                    config.set(to, value);
                }
                config.setComments(to, config.getComments(from));
                config.setInlineComments(to, config.getInlineComments(from));
            }
            config.set(from, null);
        });
    }

    public static ConfigMigration remove(int toVersion, String path) {
        return new ConfigMigration(toVersion, "remove " + path, config -> config.set(path, null));
    }
}
