package dev.stonestats.plugin.manager;

import com.google.common.collect.Multimap;
import dev.stonestats.plugin.StoneStats;
import dev.stonestats.plugin.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the "live" gear-display items for the Combat &amp; Weapons and
 * Tools &amp; Resources sections. Unlike the generic config-driven stat
 * items (same template + placeholder map for every player), each piece of
 * equipment needs its own material, name and attribute values pulled
 * straight from the player's actual inventory - so these items are built
 * in code rather than from a single reusable template.
 * <p>
 * Only works for players who are currently online (equipment isn't part
 * of the persisted stats - Bukkit simply has no inventory to read for an
 * offline player). {@link GuiManager} falls back to a config-defined
 * "offline" placeholder item when the viewed target isn't online.
 */
public class EquipmentManager {

    private final StoneStats plugin;

    public EquipmentManager(StoneStats plugin) {
        this.plugin = plugin;
    }

    /**
     * @return a display copy of {@code equipped} with generated attribute
     * lore, or {@code null} if the slot is empty (caller decides what to
     * show instead).
     */
    public ItemStack buildGearItem(ItemStack equipped, EquipmentSlot slot, String accentGradientTag) {
        if (equipped == null || equipped.getType().isAir()) {
            return null;
        }

        ItemStack display = equipped.clone();
        display.setAmount(1);
        ItemMeta meta = display.getItemMeta();
        if (meta == null) {
            return display;
        }

        MessageManager mm = plugin.getMessageManager();
        String materialName = TextUtil.formatMaterialName(equipped.getType());
        meta.displayName(mm.format("<" + accentGradientTag + ">" + materialName + "</gradient>", null));

        List<Component> lore = new ArrayList<>();

        double attackDamage = sumAttribute(equipped, meta, Attribute.ATTACK_DAMAGE, slot);
        double attackSpeed = sumAttribute(equipped, meta, Attribute.ATTACK_SPEED, slot);
        double armor = sumAttribute(equipped, meta, Attribute.ARMOR, slot);
        double toughness = sumAttribute(equipped, meta, Attribute.ARMOR_TOUGHNESS, slot);

        if (attackDamage > 0) {
            // Fists deal 1 base damage - vanilla attack-damage attribute
            // modifiers are additive on top of that base.
            lore.add(mm.format("&8✦ &f&l" + formatNumber(attackDamage + 1) + " &7Attack Damage", null));
        }
        if (attackSpeed > 0) {
            lore.add(mm.format("&8✦ &f&l" + formatNumber(attackSpeed + 4) + " &7Attack Speed", null));
        }
        if (armor > 0) {
            lore.add(mm.format("&8✦ &f&l+" + formatNumber(armor) + " &7Armor", null));
        }
        if (toughness > 0) {
            lore.add(mm.format("&8✦ &f&l+" + formatNumber(toughness) + " &7Armor Toughness", null));
        }

        int efficiencyLevel = equipped.getEnchantmentLevel(Enchantment.EFFICIENCY);
        if (efficiencyLevel > 0) {
            // Cosmetic display value, not the literal vanilla mining-speed
            // formula - just a readable "the higher the faster" indicator.
            lore.add(mm.format("&8✦ &f&l+" + (efficiencyLevel * 4) + " &7Mining Speed", null));
        }

        if (meta instanceof Damageable damageable && equipped.getType().getMaxDurability() > 0) {
            int max = equipped.getType().getMaxDurability();
            int current = max - damageable.getDamage();
            double ratio = current / (double) max;
            String bar = TextUtil.progressBar(ratio, 10, '█', '░');
            lore.add(mm.format(" ", null));
            lore.add(mm.format("<" + accentGradientTag + ">" + bar + "</gradient> &7" + TextUtil.formatPercent(ratio) + "%", null));
            lore.add(mm.format("&8Durability: &7" + current + " / " + max, null));
        }

        meta.lore(lore);
        // Same reasoning as GuiManager#buildItem: we render our own
        // attribute lines above, so the vanilla attribute/enchant tooltip
        // Minecraft would otherwise append is hidden to avoid duplicated,
        // untranslated text underneath our custom lore.
        meta.addItemFlags(ItemFlag.values());
        display.setItemMeta(meta);
        return display;
    }

    /**
     * Uses the item's own attribute modifiers if it has custom ones set
     * for this slot, otherwise falls back to Material#getDefaultAttributeModifiers
     * - i.e. the same values vanilla's own tooltip would show, without a
     * hand-maintained per-item lookup table.
     */
    private double sumAttribute(ItemStack item, ItemMeta meta, Attribute attribute, EquipmentSlot slot) {
        Multimap<Attribute, AttributeModifier> modifiers = null;
        if (meta.hasAttributeModifiers()) {
            modifiers = meta.getAttributeModifiers(slot);
        }
        if (modifiers == null || modifiers.isEmpty()) {
            modifiers = item.getType().getDefaultAttributeModifiers(slot);
        }
        double total = 0;
        for (AttributeModifier modifier : modifiers.get(attribute)) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                total += modifier.getAmount();
            }
        }
        return total;
    }

    private String formatNumber(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.US, "%.1f", value);
    }

    public ItemStack getMainHand(Player player) {
        return player.getInventory().getItemInMainHand();
    }

    public ItemStack getOffHand(Player player) {
        return player.getInventory().getItemInOffHand();
    }

    public ItemStack getHelmet(Player player) {
        return player.getInventory().getHelmet();
    }

    public ItemStack getChestplate(Player player) {
        return player.getInventory().getChestplate();
    }

    public ItemStack getLeggings(Player player) {
        return player.getInventory().getLeggings();
    }

    public ItemStack getBoots(Player player) {
        return player.getInventory().getBoots();
    }
}
