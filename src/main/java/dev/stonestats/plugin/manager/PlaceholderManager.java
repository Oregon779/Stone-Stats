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
import java.util.function.LongFunction;

/**
 * Builds the {@code {placeholder}} -> value map used when rendering the
 * stats GUI. Adding a new tracked stat later only means adding one more
 * entry here (plus the matching field on {@link PlayerStats}) - the GUI
 * and command code never need to change.
 */
public class PlaceholderManager {

    private static final String UNTRACKED = "-";

    private final StoneStats plugin;

    public PlaceholderManager(StoneStats plugin) {
        this.plugin = plugin;
    }

    public Map<String, String> buildStatPlaceholders(OfflinePlayer target, PlayerStats stats) {
        Map<String, String> placeholders = new HashMap<>();

        placeholders.put("player", target.getName() != null ? target.getName() : "Unknown");
        placeholders.put("player_sc", TextUtil.toSmallCaps(target.getName() != null ? target.getName() : "Unknown"));
        placeholders.put("uuid", target.getUniqueId().toString());

        // Disabled stats (track.* false) render "-", as config.yml documents.
        ConfigManager cfg = plugin.getConfigManager();
        int kills = stats.getKills();
        int deaths = stats.getDeaths();
        placeholders.put("stat_kills", cfg.isTrackKills() ? String.valueOf(kills) : UNTRACKED);
        placeholders.put("stat_deaths", cfg.isTrackDeaths() ? String.valueOf(deaths) : UNTRACKED);
        placeholders.put("stat_kd", cfg.isTrackKills() && cfg.isTrackDeaths() ? formatRatio(kills, deaths) : UNTRACKED);
        placeholders.put("stat_mob_kills", cfg.isTrackMobKills() ? String.valueOf(stats.getMobKills()) : UNTRACKED);
        placeholders.put("stat_blocks_broken", cfg.isTrackBlocksBroken() ? String.valueOf(stats.getBlocksBroken()) : UNTRACKED);
        placeholders.put("stat_blocks_placed", cfg.isTrackBlocksPlaced() ? String.valueOf(stats.getBlocksPlaced()) : UNTRACKED);
        placeholders.put("stat_playtime", cfg.isTrackPlaytime() ? formatDuration(stats.getLivePlaytimeSeconds()) : UNTRACKED);
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

    /** Precompiled rival-gui.compare texts; {diff} and {rival_player} are filled in per render. */
    public record CompareFormats(String ahead, String behind, String tie) {
    }

    /**
     * Placeholders for the rival GUI: every regular placeholder twice
     * (prefixed self_ / rival_), plus one {compare_x} verdict per stat and
     * the number of stats each side leads in.
     */
    public Map<String, String> buildRivalPlaceholders(OfflinePlayer self, PlayerStats selfStats,
                                                      OfflinePlayer rival, PlayerStats rivalStats,
                                                      CompareFormats formats) {
        Map<String, String> placeholders = new HashMap<>();
        buildStatPlaceholders(self, selfStats).forEach((key, value) -> placeholders.put("self_" + key, value));
        buildStatPlaceholders(rival, rivalStats).forEach((key, value) -> placeholders.put("rival_" + key, value));

        ConfigManager cfg = plugin.getConfigManager();
        Comparison c = new Comparison(formats, placeholders.get("rival_player"));
        placeholders.put("compare_kills", c.compare(cfg.isTrackKills(),
                selfStats.getKills(), rivalStats.getKills(), false, String::valueOf));
        placeholders.put("compare_deaths", c.compare(cfg.isTrackDeaths(),
                selfStats.getDeaths(), rivalStats.getDeaths(), true, String::valueOf));
        // K/D and playtime are compared at display precision, so the verdict
        // never reads "ahead by 0.0" or "ahead by 0m".
        placeholders.put("compare_kd", c.compare(cfg.isTrackKills() && cfg.isTrackDeaths(),
                Math.round(kdRatio(selfStats.getKills(), selfStats.getDeaths()) * 10),
                Math.round(kdRatio(rivalStats.getKills(), rivalStats.getDeaths()) * 10),
                false, tenths -> String.format(Locale.US, "%.1f", tenths / 10.0)));
        placeholders.put("compare_mob_kills", c.compare(cfg.isTrackMobKills(),
                selfStats.getMobKills(), rivalStats.getMobKills(), false, String::valueOf));
        placeholders.put("compare_playtime", c.compare(cfg.isTrackPlaytime(),
                selfStats.getLivePlaytimeSeconds() / 60, rivalStats.getLivePlaytimeSeconds() / 60,
                false, minutes -> formatDuration(minutes * 60)));
        placeholders.put("compare_blocks_broken", c.compare(cfg.isTrackBlocksBroken(),
                selfStats.getBlocksBroken(), rivalStats.getBlocksBroken(), false, String::valueOf));
        placeholders.put("compare_blocks_placed", c.compare(cfg.isTrackBlocksPlaced(),
                selfStats.getBlocksPlaced(), rivalStats.getBlocksPlaced(), false, String::valueOf));

        placeholders.put("compare_self_leads", String.valueOf(c.selfLeads));
        placeholders.put("compare_rival_leads", String.valueOf(c.rivalLeads));
        return placeholders;
    }

    private static final class Comparison {
        private final CompareFormats formats;
        private final String rivalName;
        private int selfLeads;
        private int rivalLeads;

        private Comparison(CompareFormats formats, String rivalName) {
            this.formats = formats;
            this.rivalName = rivalName;
        }

        private String compare(boolean tracked, long self, long rival, boolean lowerWins, LongFunction<String> diffFormatter) {
            if (!tracked) {
                return UNTRACKED;
            }
            String template;
            if (self == rival) {
                template = formats.tie();
            } else if (lowerWins ? self < rival : self > rival) {
                selfLeads++;
                template = formats.ahead();
            } else {
                rivalLeads++;
                template = formats.behind();
            }
            return template
                    .replace("{diff}", diffFormatter.apply(Math.abs(self - rival)))
                    .replace("{rival_player}", rivalName);
        }
    }

    private double kdRatio(int kills, int deaths) {
        return deaths <= 0 ? kills : kills / (double) deaths;
    }

    private String formatRatio(int kills, int deaths) {
        return String.format(Locale.US, "%.1f", kdRatio(kills, deaths));
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
        return new SimpleDateFormat(plugin.getConfigManager().getDateFormat()).format(new Date(millis));
    }
}
