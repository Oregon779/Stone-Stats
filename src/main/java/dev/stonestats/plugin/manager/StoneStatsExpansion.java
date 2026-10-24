package dev.stonestats.plugin.manager;

import dev.stonestats.plugin.StoneStats;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * Stone Stats' own PlaceholderAPI placeholders, for scoreboards, tab lists,
 * holograms and other plugins:
 * <ul>
 *   <li>{@code %stonestats_<stat>%} - the player the placeholder is parsed for</li>
 *   <li>{@code %stonestats_<stat>_<player>%} - any player by name</li>
 * </ul>
 * {@code <stat>} is any key of {@link PlaceholderManager#STAT_KEYS}.
 * <p>
 * PERFORMANCE: scoreboard plugins may parse placeholders every tick for every
 * player, and possibly off the main thread. Lookups therefore only use memory:
 * online players and the server's name cache - never a Mojang request.
 */
public class StoneStatsExpansion extends PlaceholderExpansion {

    private final StoneStats plugin;

    public StoneStatsExpansion(StoneStats plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "stonestats";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    // Registered by Stone Stats itself, so /papi reload must not drop it.
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(java.util.Locale.ROOT);
        OfflinePlayer target = player;

        if (!PlaceholderManager.STAT_KEYS.contains(key)) {
            // "<stat>_<name>": stat keys contain underscores themselves, so take the
            // longest stat key that is followed by "_".
            String stat = null;
            for (String candidate : PlaceholderManager.STAT_KEYS) {
                if (key.startsWith(candidate + "_") && (stat == null || candidate.length() > stat.length())) {
                    stat = candidate;
                }
            }
            if (stat == null) {
                return null;
            }
            target = findPlayer(params.substring(stat.length() + 1));
            if (target == null) {
                return "";
            }
            key = stat;
        }
        if (target == null) {
            return "";
        }
        return plugin.getPlaceholderManager().statValue(target, plugin.getStatsManager().getView(target.getUniqueId()), key);
    }

    private OfflinePlayer findPlayer(String name) {
        OfflinePlayer online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && plugin.getStatsManager().hasPlayed(cached.getUniqueId()) ? cached : null;
    }
}
