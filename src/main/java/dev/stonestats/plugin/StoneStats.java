package dev.stonestats.plugin;

import dev.stonestats.plugin.command.StatsCommand;
import dev.stonestats.plugin.command.StoneStatsCommand;
import dev.stonestats.plugin.listener.GuiListener;
import dev.stonestats.plugin.listener.StatsListener;
import dev.stonestats.plugin.manager.ConfigManager;
import dev.stonestats.plugin.manager.EquipmentManager;
import dev.stonestats.plugin.manager.GuiManager;
import dev.stonestats.plugin.manager.MessageManager;
import dev.stonestats.plugin.manager.PlaceholderApiHook;
import dev.stonestats.plugin.manager.PlaceholderManager;
import dev.stonestats.plugin.manager.StatsManager;
import dev.stonestats.plugin.manager.UpdateChecker;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class StoneStats extends JavaPlugin {

    private ConfigManager configManager;
    private MessageManager messageManager;
    private StatsManager statsManager;
    private PlaceholderManager placeholderManager;
    private PlaceholderApiHook placeholderApiHook;
    private EquipmentManager equipmentManager;
    private GuiManager guiManager;
    private UpdateChecker updateChecker;

    @Override
    public void onEnable() {
        getLogger().info("Loading configuration...");
        configManager = new ConfigManager(this);
        configManager.load();

        placeholderApiHook = new PlaceholderApiHook();
        if (placeholderApiHook.isAvailable()) {
            getLogger().info("PlaceholderAPI found - %placeholder% support enabled in config.yml.");
        }

        getLogger().info("Loading messages (" + configManager.getLanguage() + ")...");
        messageManager = new MessageManager(this);
        messageManager.load();

        getLogger().info("Loading player stats...");
        statsManager = new StatsManager(this);
        statsManager.load();

        placeholderManager = new PlaceholderManager(this);
        equipmentManager = new EquipmentManager(this);

        getLogger().info("Building stats GUI layout...");
        guiManager = new GuiManager(this);
        guiManager.load();

        updateChecker = new UpdateChecker(this);

        getLogger().info("Registering commands...");
        registerCommands();

        getLogger().info("Registering listeners...");
        registerListeners();

        getLogger().info("Starting stats autosave...");
        statsManager.startAutoSave();

        getLogger().info("Starting update checker...");
        updateChecker.start();

        getLogger().info("Stone Stats has been enabled - use /stats to open the stats GUI.");
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) {
            updateChecker.stop();
        }
        if (statsManager != null) {
            statsManager.stopAutoSave();
            statsManager.saveNow();
        }
        getLogger().info("Stone Stats has been disabled.");
    }

    public void reload() {
        configManager.reload();
        placeholderApiHook = new PlaceholderApiHook();
        messageManager.load();
        guiManager.load();
        statsManager.startAutoSave();
        updateChecker.start();
    }

    private void registerCommands() {
        StatsCommand statsCommand = new StatsCommand(this);
        getCommand("stats").setExecutor((CommandExecutor) statsCommand);
        getCommand("stats").setTabCompleter((TabCompleter) statsCommand);

        StoneStatsCommand adminCommand = new StoneStatsCommand(this);
        getCommand("stonestats").setExecutor((CommandExecutor) adminCommand);
        getCommand("stonestats").setTabCompleter((TabCompleter) adminCommand);
    }

    private void registerListeners() {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents((Listener) new StatsListener(this), (Plugin) this);
        pm.registerEvents((Listener) new GuiListener(this), (Plugin) this);
        pm.registerEvents((Listener) updateChecker, (Plugin) this);
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }

    public PlaceholderManager getPlaceholderManager() {
        return placeholderManager;
    }

    public PlaceholderApiHook getPlaceholderApiHook() {
        return placeholderApiHook;
    }

    public EquipmentManager getEquipmentManager() {
        return equipmentManager;
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }
}
