package dev.stonestats.plugin.manager;

import dev.stonestats.plugin.StoneStats;
import dev.stonestats.plugin.model.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * written to disk in one batch, asynchronously, on an interval, shortly
 * after quits (coalesced) and on disable, and only if something actually
 * changed since the last save.
 */
public class StatsManager {

    private static final String FILE_NAME = "stats.yml";

    // Quits only request a save; everything requested within this window is
    // written once. A full save of 100k players costs seconds of CPU and a
    // large temporary heap, so one save per quit melts down on a busy server
    // (e.g. hundreds of quits during a restart countdown).
    private static final long SAVE_DEBOUNCE_TICKS = 200L;

    private final StoneStats plugin;
    private final Map<UUID, PlayerStats> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean saveQueued = new AtomicBoolean(false);
    // Serializes snapshot + write, so a slower writer can never replace a
    // newer file with an older snapshot.
    private final Object saveLock = new Object();
    private volatile File dataFile;
    // Set when stats.yml exists but can neither be read nor moved aside -
    // saving would then overwrite the only copy of everyone's stats.
    private volatile boolean savingBlocked;
    private BukkitTask autoSaveTask;

    public StatsManager(StoneStats plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        cache.clear();
        savingBlocked = false;
        dataFile = file;
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            return;
        }

        YamlConfiguration data = new YamlConfiguration();
        try {
            data.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            // loadConfiguration() would swallow this and hand back an empty
            // config - the next autosave would then wipe every player's stats.
            quarantineUnreadableFile(file, ex);
            return;
        }

        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String uuidString : players.getKeys(false)) {
            ConfigurationSection entry = players.getConfigurationSection(uuidString);
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidString);
            } catch (IllegalArgumentException ex) {
                uuid = null;
            }
            if (uuid == null || entry == null) {
                plugin.getLogger().warning("Skipping invalid entry in " + FILE_NAME + ": " + uuidString);
                continue;
            }
            PlayerStats stats = new PlayerStats();
            stats.setKills(entry.getInt("kills", 0));
            stats.setDeaths(entry.getInt("deaths", 0));
            stats.setMobKills(entry.getInt("mob-kills", 0));
            stats.setBlocksBroken(entry.getInt("blocks-broken", 0));
            stats.setBlocksPlaced(entry.getInt("blocks-placed", 0));
            stats.addPlaytimeSeconds(entry.getLong("playtime-seconds", 0L));
            stats.setFirstJoinMillis(entry.getLong("first-join", 0L));
            stats.setLastJoinMillis(entry.getLong("last-join", 0L));
            cache.put(uuid, stats);
        }
    }

    private void quarantineUnreadableFile(File file, Exception cause) {
        File aside = new File(file.getParentFile(), FILE_NAME + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), aside.toPath());
            plugin.getLogger().severe("Could not read " + FILE_NAME + " (" + cause.getMessage() + "). It was moved to "
                    + aside.getName() + " and stats start empty - restore that file manually if needed.");
        } catch (IOException moveEx) {
            savingBlocked = true;
            plugin.getLogger().severe("Could not read " + FILE_NAME + " (" + cause.getMessage() + ") and could not move it aside ("
                    + moveEx.getMessage() + "). Saving is DISABLED so the file isn't overwritten - fix or remove it, then restart.");
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

    /**
     * Folds every running playtime session into its total. Needed on
     * disable: Paper disables plugins before it kicks players on shutdown,
     * so no quit event would ever do this for the players still online.
     */
    public void endAllSessions() {
        for (PlayerStats stats : cache.values()) {
            stats.endSession();
        }
    }

    /** Coalesces bursts of save requests into a single background save. */
    public void requestSave() {
        if (saveQueued.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                saveQueued.set(false);
                saveIfDirty();
            }, SAVE_DEBOUNCE_TICKS);
        }
    }

    private void saveIfDirty() {
        if (dirty.compareAndSet(true, false) && !saveNow()) {
            dirty.set(true);
        }
    }

    /**
     * Writes a fresh snapshot on the calling thread, regardless of the dirty
     * flag. Used directly on disable so recent stats survive a restart.
     *
     * @return whether the file was written
     */
    public boolean saveNow() {
        File file = dataFile;
        if (file == null || savingBlocked) {
            return false;
        }
        synchronized (saveLock) {
            String content = serialize();
            try {
                writeAtomically(file.toPath(), content);
                return true;
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not save " + FILE_NAME + ": " + ex.getMessage());
                return false;
            }
        }
    }

    /**
     * Produces exactly what YamlConfiguration would write for this data, but
     * straight into one StringBuilder: about 20x faster and far less garbage
     * than building a MemorySection tree for every player.
     */
    String serialize() {
        if (cache.isEmpty()) {
            return "players: {}\n";
        }
        // Snapshot iteration over a ConcurrentHashMap is safe (weakly
        // consistent) while the main thread keeps incrementing counters -
        // worst case a value is one event behind, never a torn read.
        StringBuilder sb = new StringBuilder(cache.size() * 240);
        sb.append("players:\n");
        for (Map.Entry<UUID, PlayerStats> entry : cache.entrySet()) {
            PlayerStats stats = entry.getValue();
            sb.append("  ").append(entry.getKey()).append(":\n");
            appendValue(sb, "kills", stats.getKills());
            appendValue(sb, "deaths", stats.getDeaths());
            appendValue(sb, "mob-kills", stats.getMobKills());
            appendValue(sb, "blocks-broken", stats.getBlocksBroken());
            appendValue(sb, "blocks-placed", stats.getBlocksPlaced());
            appendValue(sb, "playtime-seconds", stats.getPlaytimeSeconds());
            appendValue(sb, "first-join", stats.getFirstJoinMillis());
            appendValue(sb, "last-join", stats.getLastJoinMillis());
        }
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, String key, long value) {
        sb.append("    ").append(key).append(": ").append(value).append('\n');
    }

    /**
     * Write to a temp file, flush it to disk, then rename over the real file:
     * a crash mid-write leaves the previous stats.yml intact instead of a
     * truncated one.
     */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
