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

    /** Whose head a PLAYER_HEAD item shows: the GUI's main player, the rival (rival GUI only), or nobody. */
    private enum SkullOwner { NONE, PRIMARY, RIVAL }

    /** Parsed-once stat item: slot/material plus precompiled text lines. */
    private record CompiledStatItem(int slot, Material material, Line name, List<Line> lore, SkullOwner skullOwner) {
    }

    /**
     * One precompiled MiniMessage template. Lines without {placeholders} or
     * %papi% placeholders (labels, spacers) are rendered once on load - about
     * 60% of the MiniMessage work of an open in the default layout.
     */
    private record Line(String template, Component fixed) {
        Component render(MessageManager mm, Map<String, String> placeholders, OfflinePlayer target) {
            return fixed != null ? fixed : mm.renderPrecompiled(template, placeholders, target);
        }
    }

    /** One parsed GUI (the regular stats GUI under gui.*, or the rival GUI under rival-gui.*). */
    private record Layout(int size, boolean fillEmptySlots, String titleTemplate, ItemStack filler,
                          List<CompiledStatItem> items) {
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
    private Layout mainLayout;
    private Layout rivalLayout;
    private PlaceholderManager.CompareFormats compareFormats;
    private List<EquipmentSlotDef> equipmentSlots = new ArrayList<>();
    private final Map<String, SoundSetting> soundSettings = new HashMap<>();
    private ItemStack offlineGearTemplate;

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

        mainLayout = parseLayout(cfg, mm, "gui", cfg.getGuiRows(), "&6{player}'s Stats");
        int rivalRows = Math.max(1, Math.min(6, cfg.getInt("rival-gui.rows", 3)));
        rivalLayout = parseLayout(cfg, mm, "rival-gui", rivalRows, "&6{self_player} vs {rival_player}");
        compareFormats = new PlaceholderManager.CompareFormats(
                mm.precompile(cfg.getString("rival-gui.compare.ahead", "&a▲ +{diff}")),
                mm.precompile(cfg.getString("rival-gui.compare.behind", "&c▼ {rival_player} +{diff}")),
                mm.precompile(cfg.getString("rival-gui.compare.tie", "&e●")));

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

        equipmentSlots = parseEquipmentSlots(cfg, mainLayout.size());
    }

    private Layout parseLayout(ConfigManager cfg, MessageManager mm, String base, int rows, String defaultTitle) {
        int size = rows * 9;
        boolean fillEmptySlots = cfg.getBoolean(base + ".fill-empty-slots", true);
        String titleTemplate = mm.precompile(cfg.getString(base + ".title", defaultTitle));

        Material fillerMaterial = parseMaterial(cfg.getString(base + ".filler-item", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = new ItemStack(fillerMaterial);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.displayName(mm.format(cfg.getString(base + ".filler-name", " "), null));
            fillerMeta.addItemFlags(ItemFlag.values());
            filler.setItemMeta(fillerMeta);
        }

        return new Layout(size, fillEmptySlots, titleTemplate, filler, parseStatItems(cfg, mm, base + ".items", size));
    }

    private List<CompiledStatItem> parseStatItems(ConfigManager cfg, MessageManager mm, String path, int size) {
        List<CompiledStatItem> parsed = new ArrayList<>();
        ConfigurationSection itemsSection = cfg.getSection(path);
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
                plugin.getLogger().warning(path + "." + key + " has an invalid slot (" + slot + "), skipping.");
                continue;
            }
            Material material = parseMaterial(entry.getString("material", "STONE"), Material.STONE);
            Line name = compileLine(mm, entry.getString("name", key));
            List<Line> lore = new ArrayList<>();
            for (String line : entry.getStringList("lore")) {
                lore.add(compileLine(mm, line));
            }
            parsed.add(new CompiledStatItem(slot, material, name, lore, parseSkullOwner(entry)));
        }
        return parsed;
    }

    private Line compileLine(MessageManager mm, String raw) {
        String template = mm.precompile(raw);
        boolean dynamic = template.indexOf('{') >= 0 || template.indexOf('%') >= 0;
        return new Line(template, dynamic ? null : mm.renderPrecompiled(template, Map.of(), null));
    }

    /** skull-owner: true / self -&gt; the GUI's main player, rival -&gt; the compared player. */
    private SkullOwner parseSkullOwner(ConfigurationSection entry) {
        if (entry.isBoolean("skull-owner")) {
            return entry.getBoolean("skull-owner") ? SkullOwner.PRIMARY : SkullOwner.NONE;
        }
        return switch (entry.getString("skull-owner", "").toLowerCase(Locale.ROOT)) {
            case "true", "self" -> SkullOwner.PRIMARY;
            case "rival" -> SkullOwner.RIVAL;
            default -> SkullOwner.NONE;
        };
    }

    private List<EquipmentSlotDef> parseEquipmentSlots(ConfigManager cfg, int size) {
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
        // Blocks without an item form (WALL_TORCH, WATER, ...) make new ItemStack() throw,
        // which would abort loading the whole GUI.
        if (!material.isItem()) {
            plugin.getLogger().warning("Material '" + raw + "' in config.yml is not an item, using " + fallback + " instead.");
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
        if (isOnCooldown(viewer)) {
            return false;
        }

        PlayerStats stats = plugin.getStatsManager().getView(target.getUniqueId());
        Map<String, String> placeholders = plugin.getPlaceholderManager().buildStatPlaceholders(target, stats);

        Inventory inventory = render(mainLayout, placeholders, target, null);
        placeEquipment(inventory, target);

        viewer.openInventory(inventory);
        playSound(viewer, "open");
        return true;
    }

    /**
     * Opens the rival GUI for {@code viewer}, comparing their own stats with
     * {@code rival}'s. Same open-cooldown as {@link #open(Player, OfflinePlayer)}.
     */
    public boolean openRival(Player viewer, OfflinePlayer rival) {
        if (isOnCooldown(viewer)) {
            return false;
        }

        StatsManager statsManager = plugin.getStatsManager();
        Map<String, String> placeholders = plugin.getPlaceholderManager().buildRivalPlaceholders(
                viewer, statsManager.getView(viewer.getUniqueId()),
                rival, statsManager.getView(rival.getUniqueId()),
                compareFormats);

        viewer.openInventory(render(rivalLayout, placeholders, viewer, rival));
        playSound(viewer, "open");
        return true;
    }

    private boolean isOnCooldown(Player viewer) {
        long cooldown = plugin.getConfigManager().getGuiOpenCooldownMillis();
        if (cooldown <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastOpenMillis.get(viewer.getUniqueId());
        if (last != null && now - last < cooldown) {
            return true;
        }
        lastOpenMillis.put(viewer.getUniqueId(), now);
        return false;
    }

    private Inventory render(Layout layout, Map<String, String> placeholders, OfflinePlayer primary, OfflinePlayer rival) {
        MessageManager mm = plugin.getMessageManager();
        Component title = mm.renderPrecompiled(layout.titleTemplate(), placeholders, primary);

        StatsHolder holder = new StatsHolder(primary.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, layout.size(), title);
        holder.setInventory(inventory);

        if (layout.fillEmptySlots()) {
            for (int slot = 0; slot < layout.size(); slot++) {
                inventory.setItem(slot, layout.filler());
            }
        }

        for (CompiledStatItem def : layout.items()) {
            inventory.setItem(def.slot(), buildItem(def, placeholders, mm, primary, rival));
        }
        return inventory;
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

    private ItemStack buildItem(CompiledStatItem def, Map<String, String> placeholders, MessageManager mm,
                                OfflinePlayer target, OfflinePlayer rival) {
        ItemStack item = new ItemStack(def.material());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(def.name().render(mm, placeholders, target));
            List<Component> lore = new ArrayList<>(def.lore().size());
            for (Line line : def.lore()) {
                lore.add(line.render(mm, placeholders, target));
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
            OfflinePlayer skullOwner = switch (def.skullOwner()) {
                case PRIMARY -> target;
                case RIVAL -> rival;
                case NONE -> null;
            };
            if (skullOwner != null && meta instanceof SkullMeta skullMeta) {
                // Real player head with that player's actual skin - a purely
                // cosmetic touch that's still config-driven (skull-owner)
                // rather than hardcoded to one slot.
                skullMeta.setOwningPlayer(skullOwner);
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
        // PERFORMANCE: this runs for every inventory click on the server. The
        // plain getHolder() copies a container block's whole state (items +
        // NBT) into a snapshot each time; getHolder(false) skips that copy.
        return inventory != null && inventory.getHolder(false) instanceof StatsHolder;
    }

    public void clearCooldown(UUID uuid) {
        lastOpenMillis.remove(uuid);
    }
}
