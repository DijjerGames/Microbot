package net.runelite.client.plugins.microbot.npckiller;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.BlockingEvent;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.events.DeathEvent;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.AWTException;

@PluginDescriptor(
        name = PluginDescriptor.Default + "NPC Killer",
        description = "Kills an NPC, loots the configured drops and banks them when the inventory is full",
        tags = {"npc", "combat", "microbot", "cow", "loot"},
        enabledByDefault = false
)
@Slf4j
public class NpcKillerPlugin extends Plugin {

    @Inject
    private NpcKillerScript script;

    @Inject
    private NpcKillerConfig config;

    @Inject
    private NpcKillerOverlay overlay;

    @Inject
    private OverlayManager overlayManager;

    /**
     * The engine's {@link DeathEvent} blocking event clicks through Death's dialogue and leaves via
     * the exit portal, which would skip the reclaim step. It is suspended while this plugin runs and
     * restored on shutdown, so the script owns Death's Office end to end.
     */
    private BlockingEvent suspendedDeathEvent;

    @Provides
    NpcKillerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(NpcKillerConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        suspendEngineDeathEvent();
        overlayManager.add(overlay);
        script.run(config);
    }

    @Override
    protected void shutDown() {
        script.shutdown();
        overlayManager.remove(overlay);
        restoreEngineDeathEvent();
    }

    private void suspendEngineDeathEvent() {
        if (suspendedDeathEvent != null) {
            return;
        }
        suspendedDeathEvent = Microbot.getBlockingEventManager().getEvents().stream()
                .filter(DeathEvent.class::isInstance)
                .findFirst()
                .orElse(null);

        if (suspendedDeathEvent != null) {
            Microbot.getBlockingEventManager().remove(suspendedDeathEvent);
            log.debug("[NpcKiller] Suspended the engine DeathEvent, this plugin handles Death's Office");
        }
    }

    private void restoreEngineDeathEvent() {
        if (suspendedDeathEvent == null) {
            return;
        }
        Microbot.getBlockingEventManager().add(suspendedDeathEvent);
        suspendedDeathEvent = null;
        log.debug("[NpcKiller] Restored the engine DeathEvent");
    }
}
