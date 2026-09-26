package dev.stonestats.plugin.config;

import dev.stonestats.plugin.PluginTestBase;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest extends PluginTestBase {

    private YamlConfiguration configOnDisk() {
        return YamlConfiguration.loadConfiguration(dataFile("config.yml"));
    }

    @Test
    void deletedGuiItemStaysDeletedButMissingOptionsAreAdded() {
        editConfig(c -> {
            c.set("gui.items.level", null);
            c.set("gui.sounds.click.pitch", null);
        });
        plugin.reload();

        YamlConfiguration onDisk = configOnDisk();
        assertFalse(onDisk.contains("gui.items.level"), "admin removed this item on purpose");
        assertTrue(onDisk.contains("gui.sounds.click.pitch"), "regular options are still migrated");
    }

    @Test
    void brokenConfigIsLeftUntouchedAndDefaultsKeepTheGuiWorking() throws Exception {
        File config = dataFile("config.yml");
        String broken = "language: en\ngui:\n  title: [broken\n";
        Files.writeString(config.toPath(), broken);

        plugin.reload();

        assertEquals(broken, Files.readString(config.toPath()));
        PlayerMock player = server.addPlayer();
        player.performCommand("stats");
        Inventory top = player.getOpenInventory().getTopInventory();
        assertTrue(plugin.getGuiManager().isStatsInventory(top));
        assertNotNull(top.getItem(4), "built-in layout is used");
    }

    @Test
    void invalidDateFormatFallsBackToDefault() {
        editConfig(c -> c.set("date-format", "dd.MM.yyyy qqq"));
        assertEquals("dd.MM.yyyy HH:mm", plugin.getConfigManager().getDateFormat());
    }

    @Test
    void nonItemMaterialFallsBackInsteadOfBreakingTheGui() {
        editConfig(c -> c.set("gui.items.kills.material", "WALL_TORCH"));
        PlayerMock player = server.addPlayer();
        player.performCommand("stats");
        Inventory top = player.getOpenInventory().getTopInventory();
        assertTrue(plugin.getGuiManager().isStatsInventory(top));
        assertNotNull(top.getItem(12));
    }

    @Test
    void languageIsCaseInsensitive() {
        editConfig(c -> c.set("language", "DE"));
        PlayerMock player = server.addPlayer();
        player.performCommand("stonestats reload");
        List<String> sent = messages(player);
        assertTrue(sent.stream().anyMatch(m -> m.contains("Berechtigung")), sent.toString());
    }

    @Test
    void untrackedStatsRenderDashInRivalComparison() {
        editConfig(c -> c.set("track.deaths", false));
        var stats = plugin.getStatsManager();
        PlayerMock self = server.addPlayer();
        PlayerMock rival = server.addPlayer();
        var placeholders = plugin.getPlaceholderManager().buildRivalPlaceholders(
                self, stats.getView(self.getUniqueId()), rival, stats.getView(rival.getUniqueId()),
                new dev.stonestats.plugin.manager.PlaceholderManager.CompareFormats("ahead", "behind", "tie"));
        assertEquals("-", placeholders.get("compare_deaths"));
        assertEquals("-", placeholders.get("compare_kd"));
        assertEquals("tie", placeholders.get("compare_kills"));
        assertEquals("0", placeholders.get("compare_self_leads"));
    }
}
