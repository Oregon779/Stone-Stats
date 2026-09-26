package dev.stonestats.plugin;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.inventory.ChestInventoryMock;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;

/**
 * MockBukkit doesn't implement Paper's Inventory#getHolder(boolean), which the
 * plugin uses to avoid block-state snapshots. Custom inventories have no block
 * behind them, so on Paper both variants return the same holder.
 */
public class PaperServerMock extends ServerMock {

    @Override
    public @NotNull InventoryMock createInventory(InventoryHolder owner, int size, @NotNull Component title) {
        return new ChestInventoryMock(owner, size) {
            @Override
            public InventoryHolder getHolder(boolean useSnapshot) {
                return getHolder();
            }
        };
    }
}
