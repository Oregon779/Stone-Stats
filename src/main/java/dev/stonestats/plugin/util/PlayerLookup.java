package dev.stonestats.plugin.util;

import dev.stonestats.plugin.StoneStats;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/**
 * Resolves a typed player name to a player who has actually played here,
 * online or offline, without ever blocking the main thread.
 */
public final class PlayerLookup {

    private PlayerLookup() {
    }

    /**
     * Calls {@code callback} on the main thread with the resolved player, or
     * with {@code null} if nobody by that name ever played on this server.
     * Skips the callback entirely if the requester logged out meanwhile.
     */
    public static void resolve(StoneStats plugin, CommandSender requester, String name, Consumer<OfflinePlayer> callback) {
        // Fast path: getPlayerExact() is an in-memory lookup, safe on the main thread.
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            callback.accept(online);
            return;
        }

        // PERFORMANCE-CRITICAL: Bukkit#getOfflinePlayer(String) can trigger a
        // blocking web request to Mojang's API the first time a name is looked
        // up, which would freeze the whole server if done on the main thread.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            // stats.yml counts too: it survives even if the world's playerdata was wiped.
            boolean known = offline.hasPlayedBefore() || plugin.getStatsManager().hasPlayed(offline.getUniqueId());

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (requester instanceof Player player && !player.isOnline()) {
                    return;
                }
                callback.accept(known ? offline : null);
            });
        });
    }
}
