package dev.stonestats.plugin.command;

import dev.stonestats.plugin.StoneStats;
import dev.stonestats.plugin.manager.MessageManager;
import dev.stonestats.plugin.manager.StatsManager;
import dev.stonestats.plugin.model.PlayerStats;
import dev.stonestats.plugin.util.PlayerLookup;
import dev.stonestats.plugin.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * /stonestats reload|help|checkupdate|reset - admin command. Only visible
 * effects for permitted users; unauthorized users are told they lack
 * permission rather than seeing partial output.
 */
public class StoneStatsCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "stonestats.admin";
    private static final String RESET_PERMISSION = "stonestats.reset";
    private static final String RESET_ALL = "all";

    // Keys match the track.* options in config.yml. First/last join are left
    // out on purpose: they're history, not counters.
    private static final Map<String, Consumer<PlayerStats>> RESETTERS = new LinkedHashMap<>();

    static {
        RESETTERS.put("kills", stats -> stats.setKills(0));
        RESETTERS.put("deaths", stats -> stats.setDeaths(0));
        RESETTERS.put("mob-kills", stats -> stats.setMobKills(0));
        RESETTERS.put("blocks-broken", stats -> stats.setBlocksBroken(0));
        RESETTERS.put("blocks-placed", stats -> stats.setBlocksPlaced(0));
        RESETTERS.put("playtime", PlayerStats::resetPlaytime);
    }

    private final StoneStats plugin;

    public StoneStatsCommand(StoneStats plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MessageManager mm = plugin.getMessageManager();

        if (!sender.hasPermission(ADMIN_PERMISSION) && !sender.hasPermission(RESET_PERMISSION)) {
            mm.sendChat(sender, "general.no-permission", null);
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (denied(sender, ADMIN_PERMISSION, mm)) {
                    return true;
                }
                plugin.reload();
                mm.sendChat(sender, "general.reload-success", null);
            }
            case "checkupdate" -> {
                if (denied(sender, ADMIN_PERMISSION, mm)) {
                    return true;
                }
                mm.sendChat(sender, "update.check-triggered", null);
                plugin.getUpdateChecker().checkNow();
            }
            case "reset" -> handleReset(sender, args, mm);
            default -> sendHelp(sender);
        }
        return true;
    }

    private boolean denied(CommandSender sender, String permission, MessageManager mm) {
        if (sender.hasPermission(permission)) {
            return false;
        }
        mm.sendChat(sender, "general.no-permission", null);
        return true;
    }

    /** /stonestats reset &lt;player&gt; &lt;stat|all&gt; - works for online and offline players. */
    private void handleReset(CommandSender sender, String[] args, MessageManager mm) {
        if (denied(sender, RESET_PERMISSION, mm)) {
            return;
        }
        String statList = String.join(", ", RESETTERS.keySet());
        if (args.length < 3) {
            mm.sendChat(sender, "reset.usage", Map.of("stats", statList));
            return;
        }

        String stat = args[2].toLowerCase(Locale.ROOT);
        if (!stat.equals(RESET_ALL) && !RESETTERS.containsKey(stat)) {
            mm.sendChat(sender, "reset.unknown-stat", Map.of("stat", TextUtil.safeInput(args[2]), "stats", statList));
            return;
        }

        String requestedName = args[1];
        String shownName = TextUtil.safeInput(requestedName);
        PlayerLookup.resolve(plugin, sender, requestedName, target -> {
            if (target == null) {
                mm.sendChat(sender, "general.player-not-found", Map.of("player", shownName));
                return;
            }
            String name = target.getName() != null ? target.getName() : shownName;
            StatsManager statsManager = plugin.getStatsManager();
            if (!statsManager.hasPlayed(target.getUniqueId())) {
                mm.sendChat(sender, "reset.no-stats", Map.of("player", name));
                return;
            }

            PlayerStats stats = statsManager.getOrCreate(target.getUniqueId());
            if (stat.equals(RESET_ALL)) {
                RESETTERS.values().forEach(resetter -> resetter.accept(stats));
            } else {
                RESETTERS.get(stat).accept(stats);
            }
            statsManager.markDirty();
            statsManager.requestSave();

            // Resets can't be undone, so leave a trace of who did what.
            plugin.getLogger().info(sender.getName() + " reset '" + stat + "' for " + name + " (" + target.getUniqueId() + ")");
            mm.sendChat(sender, stat.equals(RESET_ALL) ? "reset.success-all" : "reset.success",
                    Map.of("player", name, "stat", stat));
        });
    }

    private void sendHelp(CommandSender sender) {
        MessageManager mm = plugin.getMessageManager();
        mm.sendRaw(sender, "help.header", null);
        mm.sendRaw(sender, "help.stats", null);
        mm.sendRaw(sender, "help.rival", null);
        mm.sendRaw(sender, "help.reload", null);
        mm.sendRaw(sender, "help.checkupdate", null);
        mm.sendRaw(sender, "help.reset", null);
        mm.sendRaw(sender, "help.help", null);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission(ADMIN_PERMISSION);
        boolean reset = sender.hasPermission(RESET_PERMISSION);

        if (args.length == 1) {
            List<String> subcommands = new ArrayList<>();
            if (admin) {
                subcommands.add("reload");
            }
            if (admin || reset) {
                subcommands.add("help");
            }
            if (admin) {
                subcommands.add("checkupdate");
            }
            if (reset) {
                subcommands.add("reset");
            }
            return startingWith(subcommands, args[0]);
        }

        if (!reset || !args[0].equalsIgnoreCase("reset")) {
            return List.of();
        }
        if (args.length == 2) {
            // Online players only - no disk/network lookups while typing.
            return startingWith(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3) {
            List<String> stats = new ArrayList<>(RESETTERS.keySet());
            stats.add(RESET_ALL);
            return startingWith(stats, args[2]);
        }
        return List.of();
    }

    private static List<String> startingWith(List<String> options, String partial) {
        String lower = partial.toLowerCase(Locale.ROOT);
        // Mutable on purpose: TabCompleteEvent listeners of other plugins may edit the list.
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
