package dev.stonestats.plugin.config;

import dev.stonestats.plugin.util.SafeFiles;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Keeps a config file on disk up to date with the bundled default:
 * <ol>
 *   <li>runs versioned {@link ConfigMigration}s when {@value #VERSION_KEY} is behind,</li>
 *   <li>adds every option of the default file that's missing, with its default value and comments,</li>
 *   <li>never changes a value the admin set, and never touches a file it can't parse.</li>
 * </ol>
 * New options are inserted as text so the rest of the file keeps its exact
 * formatting; the result is re-parsed and verified before it is written. The
 * previous file is kept as {@code <name>.bak}.
 */
public final class ConfigUpdater {

    public static final String VERSION_KEY = "config-version";
    // Files written before versioning existed have no config-version key.
    private static final int UNVERSIONED = 1;

    private ConfigUpdater() {
    }

    public static UpdateResult update(JavaPlugin plugin, String resourcePath, File targetFile) throws IOException {
        return update(plugin, resourcePath, targetFile, Set.of(), List.of());
    }

    /**
     * @param userOwnedSections paths whose entries belong to the user (e.g. the
     *                          GUI item list): added whole when missing, but never
     *                          refilled - otherwise an item the user deleted would
     *                          come back on every restart.
     * @param migrations        steps for renamed/removed options, see {@link ConfigMigration}
     */
    public static UpdateResult update(JavaPlugin plugin, String resourcePath, File targetFile,
                                      Set<String> userOwnedSections, List<ConfigMigration> migrations) throws IOException {
        String defaultText = readResource(plugin, resourcePath);
        if (defaultText == null) {
            return UpdateResult.UNCHANGED;
        }
        return update(defaultText, targetFile, userOwnedSections, migrations, plugin.getLogger());
    }

    public static UpdateResult update(String defaultText, File targetFile, Set<String> userOwnedSections,
                                      List<ConfigMigration> migrations, Logger logger) throws IOException {
        String userText = Files.readString(targetFile.toPath(), StandardCharsets.UTF_8);
        YamlConfiguration current = new YamlConfiguration();
        YamlConfiguration defaults = new YamlConfiguration();
        try {
            current.loadFromString(userText);
        } catch (InvalidConfigurationException ex) {
            // Merging into what loadConfiguration() returns here (an empty config)
            // would overwrite the user's file with defaults.
            throw new IOException(targetFile.getName() + " has a YAML error, leaving it untouched", ex);
        }
        try {
            defaults.loadFromString(defaultText);
        } catch (InvalidConfigurationException ex) {
            throw new IOException("bundled default for " + targetFile.getName() + " is invalid", ex);
        }

        int migratedFrom = 0;
        if (defaults.contains(VERSION_KEY)) {
            int latest = defaults.getInt(VERSION_KEY);
            int version = current.contains(VERSION_KEY, true) ? current.getInt(VERSION_KEY) : UNVERSIONED;
            if (version > latest) {
                // Written by a newer plugin version: its layout may differ, so
                // "missing" options might just have moved. Leave it alone.
                logger.warning(targetFile.getName() + " has " + VERSION_KEY + " " + version + ", newer than this plugin's "
                        + latest + " - not updating it. Did you downgrade the plugin?");
                return UpdateResult.UNCHANGED;
            }
            if (version < latest) {
                migrations.stream()
                        .filter(m -> m.toVersion() > version && m.toVersion() <= latest)
                        .sorted(Comparator.comparingInt(ConfigMigration::toVersion))
                        .forEach(m -> {
                            logger.info("Migrating " + targetFile.getName() + " to " + VERSION_KEY + " " + m.toVersion()
                                    + ": " + m.description());
                            m.action().accept(current);
                        });
                current.set(VERSION_KEY, latest);
                // Migrations move and delete values, which only the structured
                // API can do safely; this rewrite normalizes formatting once.
                userText = current.saveToString();
                migratedFrom = version;
            }
        }

        List<String> missing = new ArrayList<>();
        collectMissing(defaults, current, userOwnedSections, missing);

        boolean reformatted = false;
        String result = userText;
        if (!missing.isEmpty()) {
            String patched = YamlTextPatcher.insertMissing(userText, defaultText, defaults, missing);
            if (patched != null && isFaithful(patched, current, defaults, missing)) {
                result = patched;
            } else {
                for (String path : missing) {
                    copyNode(defaults, current, path);
                }
                result = current.saveToString();
                reformatted = true;
            }
        }

        boolean changed = migratedFrom > 0 || !missing.isEmpty();
        if (changed) {
            Path target = targetFile.toPath();
            Files.copy(target, target.resolveSibling(targetFile.getName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            SafeFiles.writeAtomically(target, result);
        }
        return new UpdateResult(changed, missing.size(), migratedFrom, reformatted);
    }

    /** Top-most paths of the default file that the current file lacks, in default order. */
    private static void collectMissing(ConfigurationSection defaults, ConfigurationSection current,
                                       Set<String> userOwnedSections, List<String> out) {
        for (String key : defaults.getKeys(false)) {
            String path = defaults.getCurrentPath().isEmpty() ? key : defaults.getCurrentPath() + "." + key;
            if (!current.contains(key, true)) {
                out.add(path);
            } else if (defaults.isConfigurationSection(key) && current.isConfigurationSection(key)
                    && !userOwnedSections.contains(path)) {
                collectMissing(defaults.getConfigurationSection(key), current.getConfigurationSection(key),
                        userOwnedSections, out);
            }
        }
    }

    /**
     * The patched text must parse to exactly the old values plus the new
     * defaults - nothing lost, nothing changed, nothing extra.
     */
    private static boolean isFaithful(String patchedText, YamlConfiguration current, YamlConfiguration defaults,
                                      List<String> missing) {
        YamlConfiguration patched = new YamlConfiguration();
        try {
            patched.loadFromString(patchedText);
        } catch (InvalidConfigurationException ex) {
            return false;
        }
        Set<String> expected = new HashSet<>(current.getKeys(true));
        for (String path : missing) {
            expected.add(path);
            ConfigurationSection section = defaults.getConfigurationSection(path);
            if (section != null) {
                for (String key : section.getKeys(true)) {
                    expected.add(path + "." + key);
                }
            }
        }
        if (!patched.getKeys(true).equals(expected)) {
            return false;
        }
        for (String key : expected) {
            YamlConfiguration source = current.contains(key, true) ? current : defaults;
            if (!source.isConfigurationSection(key) && !Objects.equals(source.get(key), patched.get(key))) {
                return false;
            }
        }
        return true;
    }

    private static void copyNode(ConfigurationSection from, ConfigurationSection to, String path) {
        Object value = from.get(path);
        if (value instanceof ConfigurationSection section) {
            copySection(section, to.createSection(path));
        } else {
            to.set(path, value);
        }
        to.setComments(path, from.getComments(path));
        to.setInlineComments(path, from.getInlineComments(path));
    }

    /** Deep copy including comments; avoids re-parenting section objects between configs. */
    static void copySection(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            copyNode(from, to, key);
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
        try {
            String text = readResource(plugin, resourcePath);
            if (text != null) {
                defaults.loadFromString(text);
            }
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().severe("Could not load built-in " + resourcePath + ": " + ex.getMessage());
        }
        return defaults;
    }

    private static String readResource(JavaPlugin plugin, String resourcePath) throws IOException {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    sb.append(buffer, 0, read);
                }
                return sb.toString();
            }
        }
    }

    /**
     * @param addedKeys        number of options (or whole sections) added
     * @param migratedFrom     config-version before migrating, 0 if no migration ran
     * @param reformatted      true if the options couldn't be inserted as text and the
     *                         file was re-saved as a whole (values and comments kept)
     */
    public record UpdateResult(boolean changed, int addedKeys, int migratedFrom, boolean reformatted) {
        static final UpdateResult UNCHANGED = new UpdateResult(false, 0, 0, false);
    }
}
