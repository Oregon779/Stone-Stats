package dev.stonestats.plugin.manager;

import dev.stonestats.plugin.StoneStats;
import dev.stonestats.plugin.model.PlayerStats;
import dev.stonestats.plugin.model.StatsHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Builds and opens the Stone Stats GUI: a configurable-height inventory
 * (up to 6 rows / 54 slots) split into three sections - Player Overview,
 * Combat &amp; Weapons, Tools &amp; Resources - as configured under
 * {@code gui} in config.yml.
 * <p>
 * PERFORMANCE: everything that doesn't change per-player - item slots,
 * materials, precompiled MiniMessage templates, sound settings, the
 * filler item - is parsed exactly once in {@link #load()}. Opening the
 * GUI never touches the filesystem, never re-parses YAML, and never
 * re-scans strings for legacy color codes; it only does placeholder
 * substitution + MiniMessage parsing, plus (for the equipment section
 * only) reading the target's live inventory, which is unavoidable since
 * that data doesn't exist anywhere else.
 */
public class GuiManager {

    /** Parsed-once stat item: slot/material plus precompiled MiniMessage templates. */
    private record CompiledStatItem(int slot, Material material, String nameTemplate, List<String> loreTemplates,
                                     boolean skullOwner) {
    }

    /** Parsed-once sound setting - avoids Sound.valueOf() + config lookups on every click. */
    private record SoundSetting(boolean enabled, Sound sound, float volume, float pitch) {
        static final SoundSetting DISABLED = new SoundSetting(false, null, 0f, 0f);
    }

    /** One equipment slot's config: which gear slot to read, where to place it, what accent color to use. */
    private record EquipmentSlotDef(int guiSlot, EquipmentSlot bukkitSlot, String accentGradientTag,
                                     Function<Player, ItemStack> reader) {
    }

    private final StoneStats plugin;
    private List<CompiledStatItem> items = new ArrayList<>();
    private List<EquipmentSlotDef> equipmentSlots = new ArrayList<>();
    private final Map<String, SoundSetting> soundSettings = new HashMap<>();
    private ItemStack fillerTemplate;
    private ItemStack offlineGearTemplate;
    private boolean fillEmptySlots;
    private String titleTemplate = "";
    private int size = 54;

    // Per-player last-open timestamp, used to throttle /stats spam. Sized
    // by unique players who have ever opened the GUI (like PlayerStats),
    // entries are removed on quit by StatsListener - negligible footprint,
    // but guards against a burst of repeated command execution (macros,
    // double-click spam) generating a fresh Inventory + a batch of
    // Components on every single call.
    private final Map<UUID, Long> lastOpenMillis = new ConcurrentHashMap<>();

    public GuiManager(StoneStats plugin) {
        this.plugin = plugin;
    }

    public void load() {
        ConfigManager cfg = plugin.getConfigManager();
        MessageManager mm = plugin.getMessageManager();

        size = cfg.getGuiRows() * 9;
        fillEmptySlots = cfg.getBoolean("gui.fill-empty-slots", true);
        titleTemplate = mm.precompile(cfg.getString("gui.title", "&6{player}'s Stats"));

        Material fillerMaterial = parseMaterial(cfg.getString("gui.filler-item", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        fillerTemplate = new ItemStack(fillerMaterial);
        ItemMeta fillerMeta = fillerTemplate.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.displayName(mm.format(cfg.getString("gui.filler-name", " "), null));
            fillerMeta.addItemFlags(ItemFlag.values());
            fillerTemplate.setItemMeta(fillerMeta);
        }

        Material offlineMaterial = parseMaterial(cfg.getString("gui.equipment.offline-item", "BARRIER"), Material.BARRIER);
        offlineGearTemplate = new ItemStack(offlineMaterial);
        ItemMeta offlineMeta = offlineGearTemplate.getItemMeta();
        if (offlineMeta != null) {
            offlineMeta.displayName(mm.format(cfg.getString("gui.equipment.offline-name", "&8Player Offline"), null));
            List<Component> offlineLore = new ArrayList<>();
            for (String line : cfg.getStringList("gui.equipment.offline-lore")) {
                offlineLore.add(mm.format(line, null));
            }
            offlineMeta.lore(offlineLore);
            offlineMeta.addItemFlags(ItemFlag.values());
            offlineGearTemplate.setItemMeta(offlineMeta);
        }

        soundSettings.clear();
        soundSettings.put("open", parseSoundSetting(cfg, "gui.sounds.open."));
        soundSettings.put("click", parseSoundSetting(cfg, "gui.sounds.click."));

        items = parseStatItems(cfg, mm);
        equipmentSlots = parseEquipmentSlots(cfg);
    }

    private List<CompiledStatItem> parseStatItems(ConfigManager cfg, MessageManager mm) {
        List<CompiledStatItem> parsed = new ArrayList<>();
        ConfigurationSection itemsSection = cfg.getSection("gui.items");
        if (itemsSection == null) {
            return parsed;
        }
        for (String key : itemsSection.getKeys(false)) {
            ConfigurationSection entry = itemsSection.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            int slot = entry.getInt("slot", -1);
            if (slot < 0 || slot >= size) {
                plugin.getLogger().warning("gui.items." + key + " has an invalid slot (" + slot + "), skipping.");
                continue;
            }
            Material material = parseMaterial(entry.getString("material", "STONE"), Material.STONE);
            String nameTemplate = mm.precompile(entry.getString("name", key));
            List<String> loreTemplates = new ArrayList<>();
            for (String line : entry.getStringList("lore")) {
                loreTemplates.add(mm.precompile(line));
            }
            boolean skullOwner = entry.getBoolean("skull-owner", false);
            parsed.add(new CompiledStatItem(slot, material, nameTemplate, loreTemplates, skullOwner));
        }
        return parsed;
    }

    private List<EquipmentSlotDef> parseEquipmentSlots(ConfigManager cfg) {
        List<EquipmentSlotDef> parsed = new ArrayList<>();
        ConfigurationSection section = cfg.getSection("gui.equipment.slots");
        if (section == null) {
            return parsed;
        }
        EquipmentManager equipmentManager = plugin.getEquipmentManager();
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            int guiSlot = entry.getInt("slot", -1);
            if (guiSlot < 0 || guiSlot >= size) {
                plugin.getLogger().warning("gui.equipment.slots." + key + " has an invalid slot (" + guiSlot + "), skipping.");
                continue;
            }
            String accent = entry.getString("accent", "gradient:#55FFFF:#88FFFF");
            EquipmentSlot bukkitSlot;
            Function<Player, ItemStack> reader;
            switch (key.toLowerCase(Locale.ROOT)) {
                case "mainhand", "tool" -> {
                    bukkitSlot = EquipmentSlot.HAND;
                    reader = equipmentManager::getMainHand;
                }
                case "offhand" -> {
                    bukkitSlot = EquipmentSlot.OFF_HAND;
                    reader = equipmentManager::getOffHand;
                }
                case "helmet" -> {
                    bukkitSlot = EquipmentSlot.HEAD;
                    reader = equipmentManager::getHelmet;
                }
                case "chestplate" -> {
                    bukkitSlot = EquipmentSlot.CHEST;
                    reader = equipmentManager::getChestplate;
                }
                case "leggings" -> {
                    bukkitSlot = EquipmentSlot.LEGS;
                    reader = equipmentManager::getLeggings;
                }
                case "boots" -> {
                    bukkitSlot = EquipmentSlot.FEET;
                    reader = equipmentManager::getBoots;
                }
                default -> {
                    plugin.getLogger().warning("Unknown equipment slot key '" + key + "' in gui.equipment.slots, skipping.");
                    continue;
                }
            }
            parsed.add(new EquipmentSlotDef(guiSlot, bukkitSlot, accent, reader));
        }
        return parsed;
    }

    private SoundSetting parseSoundSetting(ConfigManager cfg, String base) {
        if (!cfg.getBoolean(base + "enabled", true)) {
            return SoundSetting.DISABLED;
        }
        String soundName = cfg.getString(base + "sound", null);
        if (soundName == null) {
            return SoundSetting.DISABLED;
        }
        Sound sound;
        try {
            sound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Unknown sound '" + soundName + "' at " + base + "sound, disabling this sound.");
            return SoundSetting.DISABLED;
        }
        float volume = (float) cfg.getDouble(base + "volume", 1.0);
        float pitch = (float) cfg.getDouble(base + "pitch", 1.0);
        return new SoundSetting(true, sound, volume, pitch);
    }

    private Material parseMaterial(String raw, Material fallback) {
        if (raw == null) {
            return fallback;
        }
        Material material = Material.matchMaterial(raw);
        if (material == null) {
            plugin.getLogger().warning("Unknown material '" + raw + "' in config.yml, using " + fallback + " instead.");
            return fallback;
        }
        return material;
    }

    /**
     * Opens the stats GUI for {@code viewer}, showing {@code target}'s
     * stats. Returns false without doing any work if the viewer is still
     * inside the configured open-cooldown - see {@link #lastOpenMillis}.
     */
    public boolean open(Player viewer, OfflinePlayer target) {
        long cooldown = plugin.getConfigManager().getGuiOpenCooldownMillis();
        if (cooldown > 0) {
            long now = System.currentTimeMillis();
            Long last = lastOpenMillis.get(viewer.getUniqueId());
            if (last != null && now - last < cooldown) {
                return false;
            }
            lastOpenMillis.put(viewer.getUniqueId(), now);
        }

        PlayerStats stats = plugin.getStatsManager().getView(target.getUniqueId());
        Map<String, String> placeholders = plugin.getPlaceholderManager().buildStatPlaceholders(target, stats);
        MessageManager mm = plugin.getMessageManager();

        Component title = mm.renderPrecompiled(titleTemplate, placeholders, target);

        StatsHolder holder = new StatsHolder(target.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inventory);

        if (fillEmptySlots) {
            for (int slot = 0; slot < size; slot++) {
                inventory.setItem(slot, fillerTemplate);
            }
        }

        for (CompiledStatItem def : items) {
            inventory.setItem(def.slot(), buildItem(def, placeholders, mm, target));
        }

        placeEquipment(inventory, target);

        viewer.openInventory(inventory);
        playSound(viewer, "open");
        return true;
    }

    private void placeEquipment(Inventory inventory, OfflinePlayer target) {
        if (equipmentSlots.isEmpty()) {
            return;
        }
        // Equipment only exists for a live Player object - an offline
        // target has no inventory to read, so every configured gear slot
        // falls back to the "offline" placeholder item instead.
        Player online = target.isOnline() ? target.getPlayer() : null;
        EquipmentManager equipmentManager = plugin.getEquipmentManager();

        for (EquipmentSlotDef def : equipmentSlots) {
            ItemStack display = null;
            if (online != null) {
                ItemStack equipped = def.reader().apply(online);
                display = equipmentManager.buildGearItem(equipped, def.bukkitSlot(), def.accentGradientTag());
            }
            inventory.setItem(def.guiSlot(), display != null ? display : offlineGearTemplate);
        }
    }

    private ItemStack buildItem(CompiledStatItem def, Map<String, String> placeholders, MessageManager mm, OfflinePlayer target) {
        ItemStack item = new ItemStack(def.material());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(mm.renderPrecompiled(def.nameTemplate(), placeholders, target));
            List<Component> lore = new ArrayList<>(def.loreTemplates().size());
            for (String template : def.loreTemplates()) {
                lore.add(mm.renderPrecompiled(template, placeholders, target));
            }
            meta.lore(lore);
            // PERFORMANCE/VISUAL: any weapon/tool/armor material (swords,
            // pickaxes, ...) carries vanilla attribute modifiers (attack
            // damage, attack speed, ...) that Minecraft's client
            // automatically appends below our own lore as a separate
            // "In Hauptausrüstung: ..." block. These items are purely
            // decorative stat icons, not real equipment, so every flag is
            // hidden to guarantee only our own lore is ever shown.
            meta.addItemFlags(ItemFlag.values());
            if (def.skullOwner() && meta instanceof SkullMeta skullMeta) {
                // Real player head with the target's actual skin, used for
                // the profile header item - a purely cosmetic touch that's
                // still config-driven (skull-owner: true) rather than
                // hardcoded to one slot.
                skullMeta.setOwningPlayer(target);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public void playSound(Player player, String key) {
        SoundSetting setting = soundSettings.get(key);
        if (setting == null || !setting.enabled() || setting.sound() == null) {
            return;
        }
        player.playSound(player.getLocation(), setting.sound(), setting.volume(), setting.pitch());
    }

    public boolean isStatsInventory(Inventory inventory) {
        return inventory.getHolder() instanceof StatsHolder;
    }

    public void clearCooldown(UUID uuid) {
        lastOpenMillis.remove(uuid);
    }
}
