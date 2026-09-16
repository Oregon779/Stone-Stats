package dev.stonestats.plugin.listener;

import dev.stonestats.plugin.StoneStats;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Stone Stats is a read-only display, so every interaction inside it is
 * cancelled outright - cheapest possible handling, no item comparisons,
 * no whitelist of allowed slots.
 */
public class GuiListener implements Listener {

    private final StoneStats plugin;

    public GuiListener(StoneStats plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (plugin.getGuiManager().isStatsInventory(event.getInventory())) {
            event.setCancelled(true);
            if (event.getCurrentItem() != null && event.getWhoClicked() instanceof Player player) {
                plugin.getGuiManager().playSound(player, "click");
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (plugin.getGuiManager().isStatsInventory(event.getInventory())) {
            event.setCancelled(true);
        }
    }
}
