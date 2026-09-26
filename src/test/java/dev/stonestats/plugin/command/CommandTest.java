package dev.stonestats.plugin.command;

import dev.stonestats.plugin.PluginTestBase;
import dev.stonestats.plugin.model.PlayerStats;
import dev.stonestats.plugin.model.StatsHolder;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandTest extends PluginTestBase {

    private boolean hasStatsGuiOpen(PlayerMock player) {
        return plugin.getGuiManager().isStatsInventory(player.getOpenInventory().getTopInventory());
    }

    private PlayerStats stats(PlayerMock player) {
        return plugin.getStatsManager().getView(player.getUniqueId());
    }

    private static boolean anyContains(List<String> messages, String text) {
        return messages.stream().anyMatch(m -> m.contains(text));
    }

    @Test
    void statsOpensOwnGui() {
        PlayerMock player = server.addPlayer();
        player.performCommand("stats");
        assertTrue(hasStatsGuiOpen(player));
    }

    @Test
    void viewingOthersNeedsPermission() {
        PlayerMock viewer = server.addPlayer();
        server.addPlayer("Target");
        viewer.performCommand("stats Target");
        assertFalse(hasStatsGuiOpen(viewer));
        assertTrue(anyContains(messages(viewer), "permission"));
    }

    @Test
    void adminCanViewOfflinePlayer() {
        PlayerMock target = server.addPlayer("Target");
        target.disconnect();
        PlayerMock admin = server.addPlayer();
        admin.setOp(true);

        admin.performCommand("stats Target");
        finishLookups();

        assertTrue(hasStatsGuiOpen(admin));
        Inventory top = admin.getOpenInventory().getTopInventory();
        assertEquals(target.getUniqueId(), ((StatsHolder) top.getHolder(false)).getTargetUuid());
    }

    @Test
    void rivalShowsUsageAndRejectsSelf() {
        PlayerMock player = server.addPlayer("Steve");
        player.performCommand("stats rival");
        assertTrue(anyContains(messages(player), "/stats rival"));

        player.performCommand("stats rival Steve");
        assertTrue(anyContains(messages(player), "yourself"));
        assertFalse(hasStatsGuiOpen(player));
    }

    @Test
    void rivalOpensComparisonForOnlinePlayer() {
        PlayerMock player = server.addPlayer("Steve");
        server.addPlayer("Alex");
        player.performCommand("stats rival Alex");
        assertTrue(hasStatsGuiOpen(player));
        assertEquals(27, player.getOpenInventory().getTopInventory().getSize());
    }

    @Test
    void regularPlayersCannotRivalOfflineOrVanishedPlayers() {
        PlayerMock viewer = server.addPlayer("Steve");
        PlayerMock offline = server.addPlayer("Gone");
        offline.disconnect();
        PlayerMock vanished = server.addPlayer("Staff");
        viewer.hidePlayer(plugin, vanished);

        viewer.performCommand("stats rival Gone");
        viewer.performCommand("stats rival Staff");

        assertFalse(hasStatsGuiOpen(viewer));
        List<String> sent = messages(viewer);
        assertEquals(2, sent.stream().filter(m -> m.contains("must be online")).count());
    }

    @Test
    void echoedNamesCannotCarryPlaceholdersOrTags() {
        PlayerMock player = server.addPlayer();
        player.performCommand("stats rival %parseother_{Admin}_{player_ip}%<red>x");
        List<String> sent = messages(player);
        assertEquals(1, sent.size());
        assertFalse(sent.get(0).contains("%"), sent.get(0));
        assertFalse(sent.get(0).contains("{"), sent.get(0));
    }

    @Test
    void invalidNameIsRejectedWithoutAsyncLookup() {
        PlayerMock admin = server.addPlayer();
        admin.setOp(true);
        int queuedBefore = server.getScheduler().getNumberOfQueuedAsyncTasks();
        admin.performCommand("stats ThisNameIsWayTooLongForMinecraft");
        assertEquals(queuedBefore, server.getScheduler().getNumberOfQueuedAsyncTasks());
        assertTrue(anyContains(messages(admin), "never played"));
    }

    @Test
    void resetNeedsPermission() {
        PlayerMock player = server.addPlayer();
        stats(player).setKills(5);
        player.performCommand("stonestats reset " + player.getName() + " kills");
        assertEquals(5, stats(player).getKills());
        assertTrue(anyContains(messages(player), "permission"));
    }

    @Test
    void resetSingleStatAndAll() {
        PlayerMock admin = server.addPlayer();
        admin.setOp(true);
        PlayerMock target = server.addPlayer("Target");
        PlayerStats s = stats(target);
        s.setKills(5);
        s.setDeaths(4);
        s.setBlocksBroken(100);

        admin.performCommand("stonestats reset Target kills");
        finishLookups();
        assertEquals(0, s.getKills());
        assertEquals(4, s.getDeaths());

        admin.performCommand("stonestats reset Target all");
        finishLookups();
        assertEquals(0, s.getDeaths());
        assertEquals(0, s.getBlocksBroken());
    }

    @Test
    void resetOfflinePlayer() {
        PlayerMock target = server.addPlayer("Target");
        stats(target).setMobKills(9);
        target.disconnect();
        PlayerMock admin = server.addPlayer();
        admin.setOp(true);

        admin.performCommand("stonestats reset Target mob-kills");
        finishLookups();
        assertEquals(0, stats(target).getMobKills());
    }

    @Test
    void resetUnknownStatChangesNothing() {
        PlayerMock admin = server.addPlayer();
        admin.setOp(true);
        stats(admin).setKills(3);
        admin.performCommand("stonestats reset " + admin.getName() + " %evil%");
        List<String> sent = messages(admin);
        assertTrue(anyContains(sent, "Unknown stat"));
        assertFalse(sent.stream().anyMatch(m -> m.contains("%evil%")));
        assertEquals(3, stats(admin).getKills());
    }

    @Test
    void resettingPlaytimeOfOnlinePlayerRestartsTheSession() throws Exception {
        PlayerMock admin = server.addPlayer();
        admin.setOp(true);
        PlayerStats s = stats(admin);
        s.addPlaytimeSeconds(5000);
        Field start = PlayerStats.class.getDeclaredField("sessionStartMillis");
        start.setAccessible(true);
        start.setLong(s, System.currentTimeMillis() - 600_000L);

        admin.performCommand("stonestats reset " + admin.getName() + " playtime");
        finishLookups();
        assertTrue(s.getLivePlaytimeSeconds() <= 1, "live playtime " + s.getLivePlaytimeSeconds());
        assertTrue(s.isSessionRunning());
    }

    @Test
    void tabCompletionOffersRivalAndHidesSelf() {
        PlayerMock player = server.addPlayer("Steve");
        server.addPlayer("Alex");
        PluginCommand stats = plugin.getCommand("stats");

        assertEquals(List.of("rival"), stats.tabComplete(player, "stats", new String[]{"r"}));
        assertEquals(List.of("Alex"), stats.tabComplete(player, "stats", new String[]{"rival", ""}));
    }
}
