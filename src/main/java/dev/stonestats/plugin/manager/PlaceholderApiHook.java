package dev.stonestats.plugin.manager;

import dev.stonestats.plugin.StoneStats;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * Optional PlaceholderAPI integration (softdepend, see plugin.yml). Admins
 * can use any %placeholder% from PlaceholderAPI directly in config.yml's
 * GUI item names/lore, alongside Stone Stats' own {stat_x} placeholders.
 * <p>
 * Safe when PlaceholderAPI isn't installed: {@link #apply(OfflinePlayer,
 * String)} checks {@link #available} (a simple plugin-manager string
 * lookup that never touches the PlaceholderAPI class) before making any
 * call into the actual API, so the class is never resolved unless the
 * plugin is actually present at runtime.
 */
public class PlaceholderApiHook {

    private final boolean available;

    public PlaceholderApiHook() {
        this.available = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    public boolean isAvailable() {
        return available;
    }

    public String apply(OfflinePlayer target, String text) {
        if (!available || target == null || text.indexOf('%') < 0) {
            // Guard clause: skip entirely (no PlaceholderAPI call, no
            // allocation) when the plugin isn't present, there's no
            // player context, or the text has no '%' at all - the common
            // case for most static labels.
            return text;
        }
        return PlaceholderAPI.setPlaceholders(target, text);
    }

    /**
     * Registers the %stonestats_...% placeholders. Returns an opaque handle for
     * {@link #unregisterExpansion(Object)}, or null - typed as Object so callers
     * never load the PlaceholderAPI classes when the plugin is missing.
     */
    public Object registerExpansion(StoneStats plugin) {
        if (!available) {
            return null;
        }
        StoneStatsExpansion expansion = new StoneStatsExpansion(plugin);
        return expansion.register() ? expansion : null;
    }

    public static void unregisterExpansion(Object handle) {
        if (handle != null) {
            ((StoneStatsExpansion) handle).unregister();
        }
    }
}
