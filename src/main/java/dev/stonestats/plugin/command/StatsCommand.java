package dev.stonestats.plugin.command;

import dev.stonestats.plugin.StoneStats;
import dev.stonestats.plugin.manager.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * /stats [player] - opens the Stone Stats GUI for the sender, or for
 * another player if an argument is given and the sender has
 * stonestats.others.
 */
public class StatsCommand implements CommandExecutor, TabCompleter {

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

        if (!viewer.hasPermission("stonestats.others")) {
            mm.sendChat(viewer, "general.no-permission", null);
            return true;
        }

        String requestedName = args[0];

        // Fast path: the target is online right now. getPlayerExact() is a
        // plain in-memory lookup by exact name - no I/O, safe to call
        // straight from the main thread.
        Player online = Bukkit.getPlayerExact(requestedName);
        if (online != null) {
            openForOthers(viewer, online, mm);
            return true;
        }

        // Slow path: the target is offline. PERFORMANCE-CRITICAL -
        // Bukkit#getOfflinePlayer(String) can trigger a blocking web
        // request to Mojang's API the very first time a given name is
        // looked up (to resolve its UUID), which freezes the *entire*
        // server main thread for however long that request takes. With
        // 250+ players potentially running /stats <name> concurrently,
        // doing this synchronously is a guaranteed, repeatable way to
        // cause multi-second freezes. The lookup is therefore pushed onto
        // an async task; only the actual GUI-open (a Bukkit API call) is
        // hopped back onto the main thread afterwards.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer requested = Bukkit.getOfflinePlayer(requestedName);
            boolean known = requested.hasPlayedBefore();

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) {
                    return;
                }
                if (!known) {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("player", requestedName);
                    mm.sendChat(viewer, "general.player-not-found", placeholders);
                    return;
                }
                openForOthers(viewer, requested, mm);
            });
        });
        return true;
    }

    private void openForOthers(Player viewer, OfflinePlayer target, MessageManager mm) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", target.getName() != null ? target.getName() : "Unknown");
        mm.sendChat(viewer, "general.opening-others", placeholders);
        plugin.getGuiManager().open(viewer, target);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("stonestats.others")) {
            String partial = args[0].toLowerCase();
            // Only ever suggests online players - no disk/network lookups
            // in tab completion, which fires on every keystroke.
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(partial))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
