package dev.stonestats.plugin.manager;

import dev.stonestats.plugin.StoneStats;
import dev.stonestats.plugin.model.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds every known player's {@link PlayerStats} entirely in memory.
 * <p>
 * Performance is the whole point of this class: block/kill/join events
 * only ever touch the in-memory {@link ConcurrentHashMap} and its atomic
 * counters (see {@link PlayerStats}) - never the filesystem. Data is
 * written to disk in one batch, asynchronously, on an interval plus on
 * quit/disable, and only if something actually changed since the last
 * save (the {@code dirty} flag avoids pointless writes on quiet servers).
 */
public class StatsManager {

    private static final String FILE_NAME = "stats.yml";

    private final StoneStats plugin;
    private final Map<UUID, PlayerStats> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    // Guards the actual file write. The periodic autosave timer and the
    // extra save triggered on every player quit both run on separate
    // async threads; without this lock two of them could call
    // YamlConfiguration#save() on the same file at the same time and
    // corrupt stats.yml under load (e.g. a wave of quits during a
    // restart-warning on a 250-player server). Since every save already
    // happens off the main thread, this costs the main thread nothing.
    private final Object saveLock = new Object();
    private File dataFile;
    private BukkitTask autoSaveTask;

    public StatsManager(StoneStats plugin) {
        this.plugin = plugin;
    }

    public void load() {
        dataFile = new File(plugin.getDataFolder(), FILE_NAME);
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not create " + FILE_NAME + ": " + ex.getMessage());
            }
        }

        cache.clear();
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        if (data.isConfigurationSection("players")) {
            for (String uuidString : data.getConfigurationSection("players").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    PlayerStats stats = new PlayerStats();
                    String base = "players." + uuidString + ".";
                    stats.setKills(data.getInt(base + "kills", 0));
                    stats.setDeaths(data.getInt(base + "deaths", 0));
                    stats.setMobKills(data.getInt(base + "mob-kills", 0));
                    stats.setBlocksBroken(data.getInt(base + "blocks-broken", 0));
                    stats.setBlocksPlaced(data.getInt(base + "blocks-placed", 0));
                    stats.addPlaytimeSeconds(data.getLong(base + "playtime-seconds", 0L));
                    stats.setFirstJoinMillis(data.getLong(base + "first-join", 0L));
                    stats.setLastJoinMillis(data.getLong(base + "last-join", 0L));
                    cache.put(uuid, stats);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Skipping invalid UUID in " + FILE_NAME + ": " + uuidString);
                }
            }
        }
    }

    public void startAutoSave() {
        stopAutoSave();
        long intervalTicks = plugin.getConfigManager().getAutoSaveIntervalMinutes() * 60L * 20L;
        autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveIfDirty, intervalTicks, intervalTicks);
    }

    public void stopAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
    }

    /**
     * @return the live stats object for a UUID that has actually played
     * before, creating and caching an empty record on first access
     * (e.g. right on join). Safe to call from the main thread only for
     * players who are about to be tracked.
     */
    public PlayerStats getOrCreate(UUID uuid) {
        return cache.computeIfAbsent(uuid, u -> new PlayerStats());
    }

    /**
     * @return stats for display purposes (e.g. /stats &lt;player&gt;) without
     * creating a persistent cache entry for players who have no record yet.
     */
    public PlayerStats getView(UUID uuid) {
        PlayerStats existing = cache.get(uuid);
        return existing != null ? existing : new PlayerStats();
    }

    public boolean hasPlayed(UUID uuid) {
        return cache.containsKey(uuid);
    }

    public void markDirty() {
        dirty.set(true);
    }

    private void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            saveNow();
        }
    }

    /**
     * Forces an immediate save regardless of the dirty flag. Called on
     * quit and on plugin disable so recent stats survive a restart even
     * if the autosave interval hasn't elapsed yet.
     */
    public void saveNow() {
        // Snapshot iteration over a ConcurrentHashMap is safe (weakly
        // consistent) even while the main thread keeps incrementing
        // counters concurrently - worst case a value is one event behind,
        // never a torn/corrupt read of a single field.
        YamlConfiguration data = new YamlConfiguration();
        ConfigurationSection playersSection = data.createSection("players");
        for (Map.Entry<UUID, PlayerStats> entry : cache.entrySet()) {
            PlayerStats stats = entry.getValue();
            // Build each player's own flat section and use un-dotted
            // keys on it, instead of calling data.set("players.<uuid>.kills", ...)
            // eight times per player. The dotted-path form re-splits the
            // whole path and re-walks/creates every parent section on
            // every single call; with a large playerbase this adds up
            // every autosave cycle for no benefit.
            ConfigurationSection playerSection = playersSection.createSection(entry.getKey().toString());
            playerSection.set("kills", stats.getKills());
            playerSection.set("deaths", stats.getDeaths());
            playerSection.set("mob-kills", stats.getMobKills());
            playerSection.set("blocks-broken", stats.getBlocksBroken());
            playerSection.set("blocks-placed", stats.getBlocksPlaced());
            playerSection.set("playtime-seconds", stats.getPlaytimeSeconds());
            playerSection.set("first-join", stats.getFirstJoinMillis());
            playerSection.set("last-join", stats.getLastJoinMillis());
        }

        synchronized (saveLock) {
            try {
                data.save(dataFile);
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not save " + FILE_NAME + ": " + ex.getMessage());
            }
        }
    }

    /**
     * Same as {@link #saveNow()} but off the calling thread - use this
     * from synchronous listeners (e.g. quit) to avoid blocking the main
     * thread with file I/O.
     */
    public void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveNow);
    }
}
