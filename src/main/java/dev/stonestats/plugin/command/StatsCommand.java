package dev.stonestats.plugin.command;

import dev.stonestats.plugin.StoneStats;
import dev.stonestats.plugin.manager.MessageManager;
import dev.stonestats.plugin.util.PlayerLookup;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /stats [player] - opens the Stone Stats GUI for the sender, or for
 * another player if an argument is given and the sender has
 * stonestats.others.
 * <p>
 * /stats rival &lt;player&gt; - compares the sender's stats with another
 * player's (stonestats.rival). Offline rivals need stonestats.others,
 * the same permission that unlocks viewing offline stats in general.
 */
public class StatsCommand implements CommandExecutor, TabCompleter {

    private static final String RIVAL = "rival";

    private final StoneStats plugin;

    public StatsCommand(StoneStats plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MessageManager mm = plugin.getMessageManager();

        if (!(sender instanceof Player viewer)) {
            mm.sendChat(sender, "general.player-only", null);
            return true;
        }

        if (!viewer.hasPermission("stonestats.use")) {
            mm.sendChat(viewer, "general.no-permission", null);
            return true;
        }

        if (args.length == 0) {
            plugin.getGuiManager().open(viewer, viewer);
            return true;
        }

        if (args[0].equalsIgnoreCase(RIVAL)) {
            handleRival(viewer, args, mm);
            return true;
        }

        if (!viewer.hasPermission("stonestats.others")) {
            mm.sendChat(viewer, "general.no-permission", null);
            return true;
        }

        String requestedName = args[0];
        PlayerLookup.resolve(plugin, viewer, requestedName, target -> {
            if (target == null) {
                mm.sendChat(viewer, "general.player-not-found", Map.of("player", requestedName));
                return;
            }
            mm.sendChat(viewer, "general.opening-others", Map.of("player", nameOf(target, requestedName)));
            plugin.getGuiManager().open(viewer, target);
        });
        return true;
    }

    private void handleRival(Player viewer, String[] args, MessageManager mm) {
        if (!viewer.hasPermission("stonestats.rival")) {
            mm.sendChat(viewer, "general.no-permission", null);
            return;
        }
        if (args.length < 2) {
            mm.sendChat(viewer, "rival.usage", null);
            return;
        }

        String requestedName = args[1];
        if (!viewer.hasPermission("stonestats.others")) {
            // Without stonestats.others only players the viewer can currently
            // see count - no offline stats, and vanished staff stay hidden.
            Player online = Bukkit.getPlayerExact(requestedName);
            if (online == null || !viewer.canSee(online)) {
                mm.sendChat(viewer, "rival.not-online", Map.of("player", requestedName));
                return;
            }
            openRival(viewer, online, requestedName, mm);
            return;
        }

        PlayerLookup.resolve(plugin, viewer, requestedName, target -> {
            if (target == null) {
                mm.sendChat(viewer, "general.player-not-found", Map.of("player", requestedName));
                return;
            }
            openRival(viewer, target, requestedName, mm);
        });
    }

    private void openRival(Player viewer, OfflinePlayer rival, String requestedName, MessageManager mm) {
        if (rival.getUniqueId().equals(viewer.getUniqueId())) {
            mm.sendChat(viewer, "rival.self", null);
            return;
        }
        mm.sendChat(viewer, "rival.opening", Map.of("player", nameOf(rival, requestedName)));
        plugin.getGuiManager().openRival(viewer, rival);
    }

    private static String nameOf(OfflinePlayer player, String fallback) {
        return player.getName() != null ? player.getName() : fallback;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Only ever suggests online players - no disk/network lookups in tab
        // completion, which fires on every keystroke.
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            if (sender.hasPermission("stonestats.rival") && RIVAL.startsWith(partial)) {
                suggestions.add(RIVAL);
            }
            if (sender.hasPermission("stonestats.others")) {
                suggestions.addAll(onlineNames(sender, partial, false));
            }
            return suggestions;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase(RIVAL) && sender.hasPermission("stonestats.rival")) {
            return onlineNames(sender, args[1].toLowerCase(Locale.ROOT), true);
        }
        return List.of();
    }

    private List<String> onlineNames(CommandSender sender, String partial, boolean forRival) {
        Player self = sender instanceof Player p ? p : null;
        boolean hideInvisible = forRival && !sender.hasPermission("stonestats.others");
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (forRival && online == self) {
                continue;
            }
            if (hideInvisible && self != null && !self.canSee(online)) {
                continue;
            }
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                names.add(online.getName());
            }
        }
        return names;
    }
}
