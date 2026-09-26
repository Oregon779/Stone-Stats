package dev.stonestats.plugin.listener;

import dev.stonestats.plugin.PluginTestBase;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiListenerTest extends PluginTestBase {

    @Test
    void everyClickInsideTheGuiIsCancelled() {
        PlayerMock player = server.addPlayer();
        player.performCommand("stats");
        for (int slot : new int[]{0, 4, 12, 35}) {
            InventoryClickEvent event = player.simulateInventoryClick(player.getOpenInventory(), slot);
            assertTrue(event.isCancelled(), "slot " + slot);
        }
    }

    @Test
    void rivalGuiIsProtectedToo() {
        PlayerMock player = server.addPlayer("Steve");
        server.addPlayer("Alex");
        player.performCommand("stats rival Alex");
        assertTrue(player.simulateInventoryClick(player.getOpenInventory(), 10).isCancelled());
    }

    @Test
    void disablingThePluginClosesOpenGuis() {
        PlayerMock player = server.addPlayer();
        player.performCommand("stats");
        assertTrue(plugin.getGuiManager().isStatsInventory(player.getOpenInventory().getTopInventory()));

        server.getPluginManager().disablePlugin(plugin);
        assertFalse(plugin.getGuiManager().isStatsInventory(player.getOpenInventory().getTopInventory()));
    }
}
