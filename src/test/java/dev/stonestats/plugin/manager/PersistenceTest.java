package dev.stonestats.plugin.manager;

import dev.stonestats.plugin.PluginTestBase;
import dev.stonestats.plugin.model.PlayerStats;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceTest extends PluginTestBase {

    @Test
    void roundTripKeepsEveryValue() {
        StatsManager sm = plugin.getStatsManager();
        UUID id = UUID.randomUUID();
        PlayerStats s = sm.getOrCreate(id);
        s.setKills(7);
        s.setDeaths(3);
        s.setMobKills(41);
        s.setBlocksBroken(Integer.MAX_VALUE);
        s.setBlocksPlaced(12);
        s.addPlaytimeSeconds(9_876_543_210L);
        s.setFirstJoinMillis(1_700_000_000_000L);
        s.setLastJoinMillis(1_750_000_000_000L);

        assertTrue(sm.saveNow());
        sm.load();

        PlayerStats loaded = sm.getView(id);
        assertTrue(sm.hasPlayed(id));
        assertEquals(7, loaded.getKills());
        assertEquals(3, loaded.getDeaths());
        assertEquals(41, loaded.getMobKills());
        assertEquals(Integer.MAX_VALUE, loaded.getBlocksBroken());
        assertEquals(12, loaded.getBlocksPlaced());
        assertEquals(9_876_543_210L, loaded.getPlaytimeSeconds());
        assertEquals(1_700_000_000_000L, loaded.getFirstJoinMillis());
        assertEquals(1_750_000_000_000L, loaded.getLastJoinMillis());
    }

    @Test
    void fastSerializerWritesExactlyWhatYamlConfigurationWould() {
        StatsManager sm = plugin.getStatsManager();
        assertEquals(new YamlConfiguration() {{ createSection("players"); }}.saveToString(), sm.serialize());

        Random random = new Random(42);
        for (int i = 0; i < 300; i++) {
            PlayerStats s = sm.getOrCreate(new UUID(random.nextLong(), random.nextLong()));
            s.setKills(random.nextInt(Integer.MAX_VALUE));
            s.setDeaths(random.nextInt(1000));
            s.setMobKills(random.nextInt(1000));
            s.setBlocksBroken(random.nextInt(Integer.MAX_VALUE));
            s.setBlocksPlaced(random.nextInt(1000));
            s.addPlaytimeSeconds(random.nextLong(Long.MAX_VALUE / 2));
            s.setFirstJoinMillis(random.nextLong(Long.MAX_VALUE / 2));
            s.setLastJoinMillis(0);
        }
        String fast = sm.serialize();

        // Same data through Bukkit's own serializer, in the same order.
        YamlConfiguration reference = new YamlConfiguration();
        ConfigurationSection players = reference.createSection("players");
        for (String line : fast.split("\n")) {
            if (line.startsWith("  ") && !line.startsWith("    ")) {
                String uuid = line.substring(2, line.length() - 1);
                PlayerStats s = sm.getView(UUID.fromString(uuid));
                ConfigurationSection p = players.createSection(uuid);
                p.set("kills", s.getKills());
                p.set("deaths", s.getDeaths());
                p.set("mob-kills", s.getMobKills());
                p.set("blocks-broken", s.getBlocksBroken());
                p.set("blocks-placed", s.getBlocksPlaced());
                p.set("playtime-seconds", s.getPlaytimeSeconds());
                p.set("first-join", s.getFirstJoinMillis());
                p.set("last-join", s.getLastJoinMillis());
            }
        }
        assertEquals(reference.saveToString(), fast);
    }

    @Test
    void unreadableFileIsMovedAsideInsteadOfBeingOverwritten() throws Exception {
        File stats = dataFile("stats.yml");
        String garbage = "players:\n  abc: [unclosed\n    kills: 5\n";
        Files.writeString(stats.toPath(), garbage);

        StatsManager sm = plugin.getStatsManager();
        sm.load();
        sm.getOrCreate(UUID.randomUUID()).setKills(1);
        assertTrue(sm.saveNow());

        File[] quarantined = plugin.getDataFolder().listFiles((dir, name) -> name.startsWith("stats.yml.corrupt-"));
        assertEquals(1, quarantined.length, "corrupt file must be kept for manual recovery");
        assertEquals(garbage, Files.readString(quarantined[0].toPath()));
    }

    @Test
    void concurrentSavesNeverLeaveAnOlderSnapshotOnDisk() throws Exception {
        StatsManager sm = plugin.getStatsManager();
        UUID id = UUID.randomUUID();
        PlayerStats s = sm.getOrCreate(id);
        for (int round = 0; round < 20; round++) {
            int threads = 12;
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> workers = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ex) {
                        return;
                    }
                    s.incrementKills();
                    sm.saveNow();
                });
                workers.add(t);
                t.start();
            }
            start.countDown();
            for (Thread t : workers) {
                t.join();
            }
            YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(dataFile("stats.yml"));
            assertEquals(s.getKills(), onDisk.getInt("players." + id + ".kills"), "round " + round);
        }
    }

    @Test
    void noTempFileIsLeftBehind() {
        plugin.getStatsManager().getOrCreate(UUID.randomUUID()).setKills(1);
        assertTrue(plugin.getStatsManager().saveNow());
        assertFalse(dataFile("stats.yml.tmp").exists());
        assertTrue(dataFile("stats.yml").exists());
    }

    @Test
    void shutdownSavesThePlaytimeOfPlayersStillOnline() throws Exception {
        PlayerMock player = server.addPlayer();
        PlayerStats stats = plugin.getStatsManager().getOrCreate(player.getUniqueId());
        assertTrue(stats.isSessionRunning());
        backdateSession(stats, 90_000L);

        // Paper disables plugins before kicking players, so no quit event runs.
        server.getPluginManager().disablePlugin(plugin);

        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(dataFile("stats.yml"));
        long saved = onDisk.getLong("players." + player.getUniqueId() + ".playtime-seconds");
        assertTrue(saved >= 90, "saved playtime was " + saved);
    }

    @Test
    void burstOfSaveRequestsSchedulesOneSave() {
        StatsManager sm = plugin.getStatsManager();
        sm.getOrCreate(UUID.randomUUID()).setKills(3);
        sm.markDirty();
        int before = server.getScheduler().getPendingTasks().size();

        for (int i = 0; i < 50; i++) {
            sm.requestSave();
        }
        assertEquals(before + 1, server.getScheduler().getPendingTasks().size());
        assertFalse(dataFile("stats.yml").exists());

        server.getScheduler().performTicks(201);
        server.getScheduler().waitAsyncTasksFinished();
        assertTrue(dataFile("stats.yml").exists());
    }

    static void backdateSession(PlayerStats stats, long millis) throws Exception {
        Field start = PlayerStats.class.getDeclaredField("sessionStartMillis");
        start.setAccessible(true);
        start.setLong(stats, System.currentTimeMillis() - millis);
    }
}
