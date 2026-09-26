package dev.stonestats.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Boots a simulated Paper server with Stone Stats enabled for every test. */
public abstract class PluginTestBase {

    protected ServerMock server;
    protected StoneStats plugin;

    @BeforeEach
    void bootServer() {
        server = MockBukkit.mock(new PaperServerMock());
        plugin = MockBukkit.load(StoneStats.class);
        // Tests must never call Modrinth.
        plugin.getUpdateChecker().stop();
    }

    @AfterEach
    void stopServer() {
        MockBukkit.unmock();
    }

    protected static String plain(Component component) {
        return component == null ? null : PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** All chat messages the player received since the last call, as plain text. */
    protected static List<String> messages(PlayerMock player) {
        List<String> out = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            out.add(plain(next));
        }
        return out;
    }

    /** Lets PlayerLookup's async lookup finish and its main-thread callback run. */
    protected void finishLookups() {
        server.getScheduler().waitAsyncTasksFinished();
        server.getScheduler().performOneTick();
    }

    protected File dataFile(String name) {
        return new File(plugin.getDataFolder(), name);
    }

    protected void editConfig(Consumer<YamlConfiguration> edit) {
        File file = dataFile("config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        edit.accept(config);
        try {
            config.save(file);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        plugin.reload();
        plugin.getUpdateChecker().stop();
    }
}
