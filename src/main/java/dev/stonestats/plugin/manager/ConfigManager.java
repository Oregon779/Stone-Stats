package dev.stonestats.plugin.manager;

import dev.stonestats.plugin.StoneStats;
import dev.stonestats.plugin.config.ConfigUpdater;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ConfigManager {

    private static final String RESOURCE_PATH = "config.yml";
    private static final String DEFAULT_DATE_FORMAT = "dd.MM.yyyy HH:mm";
    // Item lists are the admin's: deleted entries must stay deleted.
    private static final Set<String> USER_OWNED_SECTIONS = Set.of("gui.items", "gui.equipment.slots", "rival-gui.items");

    private final StoneStats plugin;
    private File configFile;
    private YamlConfiguration config;

    // --- PERFORMANCE: hot-path cache -----------------------------------
    // track.* is read from BlockBreakEvent/BlockPlaceEvent/EntityDeathEvent,
    // i.e. potentially thousands of times per second with 250+ players
    // mining/farming simultaneously. YamlConfiguration#getBoolean(path) is
    // NOT a cheap field read: it splits "track.blocks-broken" on every '.'
    // and walks nested MemorySections, and the old isTracked(String) method
    // additionally allocated a brand-new "track." + stat String on every
    // single call - pure GC pressure for zero benefit. All of that is paid
    // exactly once here, in load()/reload(), and every event handler now
    // does a plain boolean field read instead.
    private boolean trackKills;
    private boolean trackDeaths;
    private boolean trackMobKills;
    private boolean trackBlocksBroken;
    private boolean trackBlocksPlaced;
    private boolean trackPlaytime;
    private long guiOpenCooldownMillis;
    private int guiRows;
    private String dateFormat = DEFAULT_DATE_FORMAT;

    public ConfigManager(StoneStats plugin) {
        this.plugin = plugin;
    }

    public void load() {
        configFile = new File(plugin.getDataFolder(), RESOURCE_PATH);
        if (!configFile.exists()) {
            plugin.saveResource(RESOURCE_PATH, false);
        }

        try {
            ConfigUpdater.UpdateResult result = ConfigUpdater.update(plugin, RESOURCE_PATH, configFile, USER_OWNED_SECTIONS);
            if (result.addedKeys() > 0) {
                plugin.getLogger().info("Added " + result.addedKeys() + " new option(s) to config.yml");
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to update config.yml: " + ex.getMessage());
        }

        config = ConfigUpdater.loadOrDefaults(plugin, RESOURCE_PATH, configFile);
        cacheHotPathValues();
    }

    private void cacheHotPathValues() {
        String pattern = config.getString("date-format", DEFAULT_DATE_FORMAT);
        try {
            new SimpleDateFormat(pattern);
            dateFormat = pattern;
        } catch (IllegalArgumentException ex) {
            // Checked once here instead of failing (and logging) on every GUI open.
            plugin.getLogger().warning("Invalid date-format '" + pattern + "' in config.yml, using " + DEFAULT_DATE_FORMAT + " instead.");
            dateFormat = DEFAULT_DATE_FORMAT;
        }
        trackKills = config.getBoolean("track.kills", true);
        trackDeaths = config.getBoolean("track.deaths", true);
        trackMobKills = config.getBoolean("track.mob-kills", true);
        trackBlocksBroken = config.getBoolean("track.blocks-broken", true);
        trackBlocksPlaced = config.getBoolean("track.blocks-placed", true);
        trackPlaytime = config.getBoolean("track.playtime", true);
        guiOpenCooldownMillis = Math.max(0, config.getInt("gui.open-cooldown-ms", 500));
        guiRows = Math.max(1, Math.min(6, config.getInt("gui.rows", 6)));
    }

    public void reload() {
        load();
    }

    public String getString(String path, String def) {
        return config.getString(path, def);
    }

    public int getInt(String path, int def) {
        return config.getInt(path, def);
    }

    public double getDouble(String path, double def) {
        return config.getDouble(path, def);
    }

    public boolean getBoolean(String path, boolean def) {
        return config.getBoolean(path, def);
    }

    public List<String> getStringList(String path) {
        return config.getStringList(path);
    }

    public ConfigurationSection getSection(String path) {
        return config.getConfigurationSection(path);
    }

    public String getLanguage() {
        // Language folders are lowercase; "DE" must not silently fall back to English on Linux.
        return config.getString("language", "en").toLowerCase(Locale.ROOT);
    }

    /** Always a valid SimpleDateFormat pattern (validated once on load). */
    public String getDateFormat() {
        return dateFormat;
    }

    // Fast, allocation-free getters for the hot-path listener checks -
    // see the field block above for why these exist instead of a generic
    // isTracked(String) lookup.
    public boolean isTrackKills() {
        return trackKills;
    }

    public boolean isTrackDeaths() {
        return trackDeaths;
    }

    public boolean isTrackMobKills() {
        return trackMobKills;
    }

    public boolean isTrackBlocksBroken() {
        return trackBlocksBroken;
    }

    public boolean isTrackBlocksPlaced() {
        return trackBlocksPlaced;
    }

    public boolean isTrackPlaytime() {
        return trackPlaytime;
    }

    public long getGuiOpenCooldownMillis() {
        return guiOpenCooldownMillis;
    }

    public int getGuiRows() {
        return guiRows;
    }

    public int getAutoSaveIntervalMinutes() {
        return Math.max(1, config.getInt("autosave-interval-minutes", 5));
    }
}
