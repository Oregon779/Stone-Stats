package dev.stonestats.plugin.command;

import dev.stonestats.plugin.StoneStats;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Makes /stats, /stonestats and their aliases run Stone Stats even if another
 * plugin registered the same name first - Bukkit gives a name to whichever
 * plugin loads first and leaves the others only "plugin:name". Runs once all
 * plugins are enabled; controlled by commands.override-conflicts.
 */
public class CommandClaimer implements Listener {

    private static final List<String> COMMANDS = List.of("stats", "stonestats");

    private final StoneStats plugin;

    public CommandClaimer(StoneStats plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        claim();
    }

    /** @return how many command names were taken over from other plugins */
    public int claim() {
        if (!plugin.getConfigManager().isOverrideCommandConflicts()) {
            return 0;
        }
        Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        int taken = 0;
        for (String name : COMMANDS) {
            PluginCommand ours = plugin.getCommand(name);
            if (ours == null) {
                continue;
            }
            for (String label : labels(name)) {
                Command current = known.get(label);
                if (current == ours) {
                    continue;
                }
                known.put(label, ours);
                if (current != null) {
                    taken++;
                    plugin.getLogger().info("/" + label + " belonged to " + owner(current)
                            + " - Stone Stats takes it over (commands.override-conflicts in config.yml).");
                }
            }
        }
        if (taken > 0) {
            // Refresh tab completion for anyone already online (e.g. after /reload).
            Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
        }
        return taken;
    }

    /**
     * Name plus aliases as declared in plugin.yml - Bukkit drops aliases it
     * couldn't register from the command object itself.
     */
    @SuppressWarnings("deprecation")
    private List<String> labels(String name) {
        List<String> labels = new ArrayList<>();
        labels.add(name);
        Map<String, Object> declared = plugin.getDescription().getCommands().get(name);
        Object aliases = declared == null ? null : declared.get("aliases");
        if (aliases instanceof List<?> list) {
            list.forEach(alias -> labels.add(String.valueOf(alias).toLowerCase(java.util.Locale.ROOT)));
        } else if (aliases != null) {
            labels.add(String.valueOf(aliases).toLowerCase(java.util.Locale.ROOT));
        }
        return labels;
    }

    private static String owner(Command command) {
        return command instanceof PluginCommand pluginCommand ? pluginCommand.getPlugin().getName() : "the server";
    }
}
