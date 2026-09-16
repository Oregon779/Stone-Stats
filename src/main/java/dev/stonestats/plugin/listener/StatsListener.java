package dev.stonestats.plugin.listener;

import dev.stonestats.plugin.StoneStats;
import dev.stonestats.plugin.manager.ConfigManager;
import dev.stonestats.plugin.manager.StatsManager;
import dev.stonestats.plugin.model.PlayerStats;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Tracks every stat shown in the GUI.
 * <p>
 * PERFORMANCE NOTE (250+ players): BlockBreakEvent/BlockPlaceEvent/
 * EntityDeathEvent are among the highest-frequency events on a populated
 * survival server - a handful of players with efficiency pickaxes or a
 * mob farm alone can fire thousands of these per second. Every handler
 * below therefore starts with a plain boolean field read on
 * {@link ConfigManager} (cached once on load/reload, see there) instead
 * of a YamlConfiguration path lookup, and bails out immediately via
 * {@code ignoreCancelled = true} + an early return before touching the
 * stats map at all when the stat is disabled. Nothing here allocates
 * beyond what {@link PlayerStats}'s lock-free atomic counters need.
 */
public class StatsListener implements Listener {

    private final StoneStats plugin;

    public StatsListener(StoneStats plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerStats stats = plugin.getStatsManager().getOrCreate(player.getUniqueId());

        long now = System.currentTimeMillis();
        if (stats.getFirstJoinMillis() <= 0) {
            stats.setFirstJoinMillis(now);
        }
        stats.setLastJoinMillis(now);

        if (plugin.getConfigManager().isTrackPlaytime()) {
            stats.startSession();
        }
        plugin.getStatsManager().markDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerStats stats = plugin.getStatsManager().getOrCreate(player.getUniqueId());
        if (plugin.getConfigManager().isTrackPlaytime()) {
            stats.endSession();
        }
        plugin.getStatsManager().markDirty();
        // Quits are infrequent relative to block/kill events, so an async
        // save here (on top of the regular autosave interval) is cheap
        // insurance against data loss without ever blocking the main
        // thread. StatsManager serializes concurrent writers internally.
        plugin.getStatsManager().saveAsync();

        // Drop the /stats open-cooldown entry for this player - otherwise
        // it would sit in memory forever for anyone who ever ran /stats,
        // however negligible that footprint is.
        plugin.getGuiManager().clearCooldown(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        ConfigManager cfg = plugin.getConfigManager();
        boolean trackDeaths = cfg.isTrackDeaths();
        boolean trackKills = cfg.isTrackKills();
        if (!trackDeaths && !trackKills) {
            // Neither stat is enabled - skip getKiller()/map lookups entirely.
            return;
        }

        StatsManager stats = plugin.getStatsManager();
        Player victim = event.getEntity();

        if (trackDeaths) {
            stats.getOrCreate(victim.getUniqueId()).incrementDeaths();
            stats.markDirty();
        }

        Player killer = victim.getKiller();
        if (trackKills && killer != null) {
            stats.getOrCreate(killer.getUniqueId()).incrementKills();
            stats.markDirty();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        // PlayerDeathEvent (handled above) already covers players; this
        // only needs to count non-player mobs killed by a player. The
        // instanceof check comes first since it's essentially free and
        // rules out the (very common) player-death case before we even
        // look at the config flag.
        if (event instanceof PlayerDeathEvent || !plugin.getConfigManager().isTrackMobKills()) {
            return;
        }
        if (event.getEntity().getKiller() instanceof Player killer) {
            plugin.getStatsManager().getOrCreate(killer.getUniqueId()).incrementMobKills();
            plugin.getStatsManager().markDirty();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getConfigManager().isTrackBlocksBroken()) {
            return;
        }
        plugin.getStatsManager().getOrCreate(event.getPlayer().getUniqueId()).incrementBlocksBroken();
        plugin.getStatsManager().markDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getConfigManager().isTrackBlocksPlaced()) {
            return;
        }
        plugin.getStatsManager().getOrCreate(event.getPlayer().getUniqueId()).incrementBlocksPlaced();
        plugin.getStatsManager().markDirty();
    }
}
