package dev.stonestats.plugin.model;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Tags an open inventory as a Stone Stats GUI so {@code GuiListener} can
 * recognise it with a fast {@code instanceof} check instead of comparing
 * titles (which is slower and breaks if the title is ever localized).
 */
public class StatsHolder implements InventoryHolder {

    private final UUID targetUuid;
    private Inventory inventory;

    public StatsHolder(UUID targetUuid) {
        this.targetUuid = targetUuid;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
