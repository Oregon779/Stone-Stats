package dev.stonestats.plugin.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigUpdaterTest {

    private static final Logger LOG = Logger.getLogger("ConfigUpdaterTest");

    @TempDir
    Path dir;

    private static String bundledConfig() throws IOException {
        try (InputStream in = ConfigUpdaterTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private File write(String text) throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, text);
        return file.toFile();
    }

    private static YamlConfiguration parse(String text) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    /** Every line of {@code original} appears in {@code result}, unchanged and in the same order. */
    private static void assertKeptVerbatim(String original, String result) {
        List<String> remaining = result.lines().toList();
        int position = 0;
        for (String line : original.lines().toList()) {
            int found = remaining.subList(position, remaining.size()).indexOf(line);
            assertTrue(found >= 0, "line lost or changed: [" + line + "]");
            position += found + 1;
        }
    }

    @Test
    void oldRealConfigGetsNewOptionsWhileEveryExistingLineStaysAsItWas() throws Exception {
        String bundled = bundledConfig();
        String old = bundled
                .replace("# Layout version of this file, used to migrate renamed or removed options\n"
                        + "# on plugin updates. Don't change it.\nconfig-version: 1\n\n", "")
                .replace("      pitch: 1.4\n", "")
                .replace(bundled.substring(bundled.indexOf("# Checks https://modrinth.com")), "")
                .replace("language: en", "language: \"de\"   # set by the admin")
                .replace("  rows: 4\n", "  rows: 5\n");
        File file = write(old);

        ConfigUpdater.UpdateResult result = ConfigUpdater.update(bundled, file, Set.of(), List.of(), LOG);
        String updated = Files.readString(file.toPath());

        assertTrue(result.changed());
        assertEquals(3, result.addedKeys(), "config-version, click.pitch, update-checker");
        assertFalse(result.reformatted(), "options must be inserted as text");
        assertKeptVerbatim(old, updated);

        YamlConfiguration config = parse(updated);
        assertEquals("de", config.getString("language"));
        assertEquals(5, config.getInt("gui.rows"));
        assertEquals(1.4, config.getDouble("gui.sounds.click.pitch"));
        assertTrue(config.getBoolean("update-checker.enabled"));
        assertEquals(60, config.getInt("update-checker.check-interval-minutes"));
        assertEquals(1, config.getInt("config-version"));
        assertTrue(updated.contains("# Checks https://modrinth.com/project/stone-stats"), "comments come along");
        assertTrue(updated.indexOf("config-version: 1") < updated.indexOf("language:"), "inserted at its default position");
        assertEquals(old, Files.readString(dir.resolve("config.yml.bak")));
    }

    @Test
    void nestedFeatureIsInsertedWithCommentAtTheAdminsIndentation() throws Exception {
        String defaults = """
                features:
                  other: true
                  # Example feature
                  example:
                    enabled: false
                    level: 3
                """;
        String old = """
                features:
                    other: false   # four-space indentation, own comment
                """;
        File file = write(old);

        ConfigUpdater.UpdateResult result = ConfigUpdater.update(defaults, file, Set.of(), List.of(), LOG);
        String updated = Files.readString(file.toPath());

        assertFalse(result.reformatted());
        assertKeptVerbatim(old, updated);
        assertTrue(updated.contains("    # Example feature\n    example:\n"), updated);
        YamlConfiguration config = parse(updated);
        assertFalse(config.getBoolean("features.other"));
        assertFalse(config.getBoolean("features.example.enabled"));
        assertEquals(3, config.getInt("features.example.level"));
    }

    @Test
    void missingLeafDeepInsideExistingSectionsIsAdded() throws Exception {
        String defaults = """
                features:
                  example:
                    enabled: true
                    mode: fast
                """;
        File file = write("features:\n  example:\n    mode: slow\n");

        ConfigUpdater.update(defaults, file, Set.of(), List.of(), LOG);

        YamlConfiguration config = parse(Files.readString(file.toPath()));
        assertTrue(config.getBoolean("features.example.enabled"));
        assertEquals("slow", config.getString("features.example.mode"));
    }

    @Test
    void upToDateFileIsNotTouchedAtAll() throws Exception {
        String bundled = bundledConfig();
        File file = write(bundled);

        ConfigUpdater.UpdateResult result = ConfigUpdater.update(bundled, file, Set.of(), List.of(), LOG);

        assertFalse(result.changed());
        assertEquals(bundled, Files.readString(file.toPath()));
        assertFalse(Files.exists(dir.resolve("config.yml.bak")));
    }

    @Test
    void layoutTheTextPatcherCantHandleFallsBackWithoutLosingValues() throws Exception {
        String defaults = "gui:\n  rows: 4\n  # Title shown on top\n  title: Stats\n";
        File file = write("gui: {rows: 6}\n");

        ConfigUpdater.UpdateResult result = ConfigUpdater.update(defaults, file, Set.of(), List.of(), LOG);

        assertTrue(result.reformatted());
        YamlConfiguration config = parse(Files.readString(file.toPath()));
        assertEquals(6, config.getInt("gui.rows"));
        assertEquals("Stats", config.getString("gui.title"));
        assertEquals(List.of("Title shown on top"), config.getComments("gui.title"));
    }

    @Test
    void userOwnedSectionIsNotRefilled() throws Exception {
        String defaults = "items:\n  a:\n    slot: 1\n  b:\n    slot: 2\n";
        File file = write("items:\n  a:\n    slot: 5\n");

        ConfigUpdater.UpdateResult result = ConfigUpdater.update(defaults, file, Set.of("items"), List.of(), LOG);

        assertFalse(result.changed());
        assertFalse(parse(Files.readString(file.toPath())).contains("items.b"));
    }

    @Test
    void migrationsRenameAndRemoveOptionsAndBumpTheVersion() throws Exception {
        String defaults = "config-version: 3\ncooldown:\n  open-ms: 500\nkeep: a\n";
        File file = write("open-cooldown-ms: 1200\nobsolete: true\nkeep: b\n");
        List<ConfigMigration> migrations = List.of(
                ConfigMigration.remove(3, "obsolete"),
                ConfigMigration.rename(2, "open-cooldown-ms", "cooldown.open-ms"));

        ConfigUpdater.UpdateResult result = ConfigUpdater.update(defaults, file, Set.of(), migrations, LOG);

        assertEquals(1, result.migratedFrom());
        YamlConfiguration config = parse(Files.readString(file.toPath()));
        assertEquals(1200, config.getInt("cooldown.open-ms"), "the admin's value survives the rename");
        assertFalse(config.contains("open-cooldown-ms"));
        assertFalse(config.contains("obsolete"));
        assertEquals("b", config.getString("keep"));
        assertEquals(3, config.getInt("config-version"));
        assertTrue(Files.exists(dir.resolve("config.yml.bak")));
    }

    @Test
    void migrationsAlreadyAppliedAreSkipped() throws Exception {
        String defaults = "config-version: 3\nnew: 1\n";
        File file = write("config-version: 2\nold: 7\nnew: 5\n");
        List<ConfigMigration> migrations = List.of(
                ConfigMigration.rename(2, "old", "new"),
                ConfigMigration.remove(3, "old"));

        ConfigUpdater.update(defaults, file, Set.of(), migrations, LOG);

        YamlConfiguration config = parse(Files.readString(file.toPath()));
        assertEquals(5, config.getInt("new"));
        assertFalse(config.contains("old"));
    }

    @Test
    void configFromNewerPluginVersionIsLeftAlone() throws Exception {
        String text = "config-version: 9\nsomething: else\n";
        File file = write(text);

        ConfigUpdater.UpdateResult result = ConfigUpdater.update("config-version: 2\nnew: 1\n", file, Set.of(), List.of(), LOG);

        assertFalse(result.changed());
        assertEquals(text, Files.readString(file.toPath()));
    }

    @Test
    void brokenYamlIsNeverOverwritten() throws Exception {
        String broken = "gui:\n  title: [unclosed\n";
        File file = write(broken);

        assertThrows(IOException.class, () -> ConfigUpdater.update("gui:\n  title: x\n", file, Set.of(), List.of(), LOG));
        assertEquals(broken, Files.readString(file.toPath()));
    }

    @Test
    void windowsLineEndingsArePreserved() throws Exception {
        File file = write("a: 1\r\nb: 2\r\n");

        ConfigUpdater.update("a: 0\nb: 0\nc: 3\n", file, Set.of(), List.of(), LOG);

        String updated = Files.readString(file.toPath());
        assertTrue(updated.startsWith("a: 1\r\nb: 2\r\n"));
        assertFalse(updated.replace("\r\n", "").contains("\n"), "no mixed line endings");
        assertEquals(3, parse(updated).getInt("c"));
    }
}
