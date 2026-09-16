package dev.stonestats.plugin.manager;

import dev.stonestats.plugin.StoneStats;
import dev.stonestats.plugin.model.PlayerStats;
import dev.stonestats.plugin.util.TextUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the {@code {placeholder}} -> value map used when rendering the
 * stats GUI. Adding a new tracked stat later only means adding one more
 * entry here (plus the matching field on {@link PlayerStats}) - the GUI
 * and command code never need to change.
 */
public class PlaceholderManager {

    private final StoneStats plugin;

    public PlaceholderManager(StoneStats plugin) {
        this.plugin = plugin;
    }

    public Map<String, String> buildStatPlaceholders(OfflinePlayer target, PlayerStats stats) {
        Map<String, String> placeholders = new HashMap<>();

        placeholders.put("player", target.getName() != null ? target.getName() : "Unknown");
        placeholders.put("player_sc", TextUtil.toSmallCaps(target.getName() != null ? target.getName() : "Unknown"));
        placeholders.put("uuid", target.getUniqueId().toString());

        int kills = stats.getKills();
        int deaths = stats.getDeaths();
        placeholders.put("stat_kills", String.valueOf(kills));
        placeholders.put("stat_deaths", String.valueOf(deaths));
        placeholders.put("stat_kd", formatRatio(kills, deaths));
        placeholders.put("stat_mob_kills", String.valueOf(stats.getMobKills()));
        placeholders.put("stat_blocks_broken", String.valueOf(stats.getBlocksBroken()));
        placeholders.put("stat_blocks_placed", String.valueOf(stats.getBlocksPlaced()));
        placeholders.put("stat_playtime", formatDuration(stats.getLivePlaytimeSeconds()));
        placeholders.put("stat_first_join", formatDate(stats.getFirstJoinMillis()));
        placeholders.put("stat_last_join", formatDate(stats.getLastJoinMillis()));

        // Level and XP need a live Player object (they aren't part of the
        // persisted stats) - only available while the target is online.
        if (target.isOnline() && target.getPlayer() instanceof Player online) {
            placeholders.put("stat_level", String.valueOf(online.getLevel()));
            double xpRatio = online.getExp();
            placeholders.put("stat_xp_bar", TextUtil.progressBar(xpRatio, 10, '█', '░'));
            placeholders.put("stat_xp_percent", TextUtil.formatPercent(xpRatio));
        } else {
            placeholders.put("stat_level", "-");
            placeholders.put("stat_xp_bar", TextUtil.progressBar(0, 10, '█', '░'));
            placeholders.put("stat_xp_percent", "0");
        }

        return placeholders;
    }

    private String formatRatio(int kills, int deaths) {
        if (deaths <= 0) {
            return String.format(Locale.US, "%.1f", (double) kills);
        }
        return String.format(Locale.US, "%.1f", kills / (double) deaths);
    }

    private String formatDuration(long totalSeconds) {
        if (totalSeconds <= 0) {
            return "0m";
        }
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (days > 0 || hours > 0) sb.append(hours).append("h ");
        sb.append(minutes).append("m");
        return sb.toString().trim();
    }

    private String formatDate(long millis) {
        if (millis <= 0) {
            return "-";
        }
        String pattern = plugin.getConfigManager().getDateFormat();
        try {
            return new SimpleDateFormat(pattern).format(new Date(millis));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid date-format '" + pattern + "' in config.yml, using default instead.");
            return new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(millis));
        }
    }
}
