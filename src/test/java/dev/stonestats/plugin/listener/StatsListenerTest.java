package dev.stonestats.plugin.listener;

import dev.stonestats.plugin.PluginTestBase;
import dev.stonestats.plugin.model.PlayerStats;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.LivingEntityMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsListenerTest extends PluginTestBase {

    private PlayerStats stats(PlayerMock player) {
        return plugin.getStatsManager().getView(player.getUniqueId());
    }

    private void cancelAll(Class<? extends org.bukkit.event.Event> type) {
        Listener canceller = new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void onBreak(BlockBreakEvent event) {
                if (type == BlockBreakEvent.class) {
                    event.setCancelled(true);
                }
            }

            @EventHandler(priority = EventPriority.LOWEST)
            public void onDeath(EntityDeathEvent event) {
                if (type == EntityDeathEvent.class) {
                    event.setCancelled(true);
                }
            }
        };
        server.getPluginManager().registerEvents(canceller, MockBukkit.createMockPlugin());
    }

    @Test
    void joinStartsSessionAndQuitEndsIt() {
        PlayerMock player = server.addPlayer();
        PlayerStats s = stats(player);
        assertTrue(s.getFirstJoinMillis() > 0);
        assertEquals(s.getFirstJoinMillis(), s.getLastJoinMillis());
        assertTrue(s.isSessionRunning());

        player.disconnect();
        assertFalse(s.isSessionRunning());
    }

    @Test
    void blocksBrokenAndPlacedAreCounted() {
        PlayerMock player = server.addPlayer();
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        player.simulateBlockBreak(block);
        player.simulateBlockPlace(Material.STONE, new Location(player.getWorld(), 1, 64, 0));

        assertEquals(1, stats(player).getBlocksBroken());
        assertEquals(1, stats(player).getBlocksPlaced());
    }

    @Test
    void cancelledBlockBreakIsNotCounted() {
        cancelAll(BlockBreakEvent.class);
        PlayerMock player = server.addPlayer();
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        player.simulateBlockBreak(block);

        assertEquals(0, stats(player).getBlocksBroken());
    }

    @Test
    void killCountsForKillerAndDeathForVictim() {
        PlayerMock killer = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        victim.setKiller(killer);
        victim.setHealth(0);

        assertEquals(1, stats(killer).getKills());
        assertEquals(1, stats(victim).getDeaths());
        assertEquals(0, stats(victim).getKills());
    }

    @Test
    void killingYourselfIsNotAKill() {
        PlayerMock player = server.addPlayer();
        player.setKiller(player);
        player.setHealth(0);

        assertEquals(0, stats(player).getKills());
        assertEquals(1, stats(player).getDeaths());
    }

    @Test
    void cancelledDeathIsNotCounted() {
        cancelAll(EntityDeathEvent.class);
        PlayerMock killer = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        victim.setKiller(killer);
        victim.setHealth(0);

        assertEquals(0, stats(killer).getKills());
        assertEquals(0, stats(victim).getDeaths());
    }

    @Test
    void mobKilledByPlayerCountsAsMobKill() {
        PlayerMock player = server.addPlayer();
        LivingEntityMock zombie = (LivingEntityMock) player.getWorld().spawnEntity(player.getLocation(), EntityType.ZOMBIE);
        zombie.setKiller(player);
        zombie.setHealth(0);

        assertEquals(1, stats(player).getMobKills());
        assertEquals(0, stats(player).getKills());
    }

    @Test
    void disabledStatIsNotCountedAndDisplaysDash() {
        editConfig(c -> c.set("track.blocks-broken", false));
        PlayerMock player = server.addPlayer();
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        player.simulateBlockBreak(block);

        assertEquals(0, stats(player).getBlocksBroken());
        assertEquals("-", plugin.getPlaceholderManager().buildStatPlaceholders(player, stats(player)).get("stat_blocks_broken"));
    }

    @Test
    void turningPlaytimeOffOnReloadEndsRunningSessions() {
        PlayerMock player = server.addPlayer();
        assertTrue(stats(player).isSessionRunning());

        editConfig(c -> c.set("track.playtime", false));
        assertFalse(stats(player).isSessionRunning());

        editConfig(c -> c.set("track.playtime", true));
        assertTrue(stats(player).isSessionRunning());
    }
}
