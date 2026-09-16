package dev.stonestats.plugin.model;

import org.bukkit.Material;

import java.util.List;

/**
 * Immutable, parsed-once representation of a single entry under
 * {@code gui.items} in config.yml. Parsed a single time when the GUI is
 * (re)loaded, then reused for every /stats open - no YAML lookups happen
 * while a player is opening the menu.
 */
public record StatItem(String key, int slot, Material material, String name, List<String> lore) {
}
