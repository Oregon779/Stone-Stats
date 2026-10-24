package dev.stonestats.plugin;

import dev.stonestats.plugin.manager.StoneStatsExpansion;
import dev.stonestats.plugin.model.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Own placeholders, command priority and the animated rival comparison. */
class FeatureCardsTest extends PluginTestBase {

    private PlayerStats stats(PlayerMock player) {
        return plugin.getStatsManager().getOrCreate(player.getUniqueId());
    }

    // --- %stonestats_...% ---------------------------------------------------

    @Test
    void placeholdersResolveForTheParsingPlayerAndForOthersByName() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock alex = server.addPlayer("Alex");
        stats(steve).setKills(12);
        stats(steve).setMobKills(40);
        stats(alex).setKills(3);
        StoneStatsExpansion expansion = new StoneStatsExpansion(plugin);

        assertEquals("12", expansion.onRequest(steve, "kills"));
        assertEquals("40", expansion.onRequest(steve, "mob_kills"));
        assertEquals("3", expansion.onRequest(steve, "kills_Alex"));
        assertEquals("40", expansion.onRequest(alex, "mob_kills_Steve"), "underscore stats parse correctly");
        assertEquals("12.0", expansion.onRequest(steve, "kd"));
        assertEquals("", expansion.onRequest(steve, "kills_NobodyHere"));
        assertNull(expansion.onRequest(steve, "unknown"), "unknown placeholders stay untouched");
    }

    @Test
    void placeholderShowsDashForUntrackedStats() {
        editConfig(c -> c.set("track.kills", false));
        PlayerMock steve = server.addPlayer("Steve");
        assertEquals("-", new StoneStatsExpansion(plugin).onRequest(steve, "kills"));
    }

    @Test
    void sameValuesInGuiAndPlaceholderApi() {
        PlayerMock steve = server.addPlayer("Steve");
        stats(steve).addPlaytimeSeconds(7_300);
        Map<String, String> gui = plugin.getPlaceholderManager().buildStatPlaceholders(steve, stats(steve));
        StoneStatsExpansion expansion = new StoneStatsExpansion(plugin);
        for (String key : new String[]{"playtime", "playtime_hours", "first_join", "level", "xp_bar"}) {
            assertEquals(gui.get("stat_" + key), expansion.onRequest(steve, key), key);
        }
        assertEquals("2", expansion.onRequest(steve, "playtime_hours"));
    }

    // --- command priority ------------------------------------------------------

    private static Command foreign(String label) {
        return new Command(label) {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return true;
            }
        };
    }

    @Test
    void conflictingCommandsAreTakenOver() {
        Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        known.put("stats", foreign("stats"));
        known.put("ss", foreign("ss"));

        assertEquals(2, plugin.getCommandClaimer().claim());
        assertSame(plugin.getCommand("stats"), known.get("stats"));
        assertSame(plugin.getCommand("stonestats"), known.get("ss"));
        assertEquals(0, plugin.getCommandClaimer().claim(), "nothing left to take over");
    }

    @Test
    void takeOverCanBeTurnedOff() {
        editConfig(c -> c.set("commands.override-conflicts", false));
        Command other = foreign("stats");
        Bukkit.getCommandMap().getKnownCommands().put("stats", other);

        assertEquals(0, plugin.getCommandClaimer().claim());
        assertSame(other, Bukkit.getCommandMap().getKnownCommands().get("stats"));
    }

    // --- animated rival comparison ------------------------------------------------

    private Inventory openRival(PlayerMock viewer, String rival) {
        viewer.performCommand("stats rival " + rival);
        return viewer.getOpenInventory().getTopInventory();
    }

    private static Material type(Inventory inventory, int slot) {
        ItemStack item = inventory.getItem(slot);
        return item == null ? Material.AIR : item.getType();
    }

    @Test
    void rivalComparisonRevealsStatsOneByOneThenTheResult() {
        PlayerMock steve = server.addPlayer("Steve");
        server.addPlayer("Alex");
        stats(steve).setKills(10);
        stats(steve).setBlocksBroken(50);

        Inventory gui = openRival(steve, "Alex");
        assertEquals(Material.NETHER_STAR, type(gui, 4), "VS is shown right away");
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, type(gui, 2), "heads hold the result, so they wait");
        for (int slot = 10; slot <= 16; slot++) {
            assertEquals(Material.BLACK_STAINED_GLASS_PANE, type(gui, slot), "slot " + slot);
        }

        server.getScheduler().performTicks(5);
        assertEquals(Material.IRON_SWORD, type(gui, 10));
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, type(gui, 11));

        server.getScheduler().performTicks(5 * 7);
        assertEquals(Material.GRASS_BLOCK, type(gui, 16));
        assertEquals(Material.PLAYER_HEAD, type(gui, 2));
        assertEquals(Boolean.TRUE, gui.getItem(2).getItemMeta().getEnchantmentGlintOverride(), "leader glows");
        assertFalse(gui.getItem(6).getItemMeta().hasEnchantmentGlintOverride(), "the other head doesn't");
    }

    @Test
    void closingTheGuiStopsTheReveal() {
        PlayerMock steve = server.addPlayer("Steve");
        server.addPlayer("Alex");
        int tasksBefore = server.getScheduler().getPendingTasks().size();

        Inventory gui = openRival(steve, "Alex");
        assertEquals(tasksBefore + 1, server.getScheduler().getPendingTasks().size());
        steve.closeInventory();
        server.getScheduler().performTicks(10);

        assertEquals(tasksBefore, server.getScheduler().getPendingTasks().size());
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, type(gui, 11), "nothing revealed after closing");
    }

    @Test
    void animationCanBeTurnedOff() {
        editConfig(c -> c.set("rival-gui.animation.enabled", false));
        PlayerMock steve = server.addPlayer("Steve");
        server.addPlayer("Alex");

        Inventory gui = openRival(steve, "Alex");
        assertEquals(Material.IRON_SWORD, type(gui, 10));
        assertEquals(Material.PLAYER_HEAD, type(gui, 2));
        assertTrue(plugin.getGuiManager().isStatsInventory(gui));
    }
}
