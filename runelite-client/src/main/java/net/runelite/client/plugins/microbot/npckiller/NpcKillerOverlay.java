package net.runelite.client.plugins.microbot.npckiller;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

@Slf4j
public class NpcKillerOverlay extends OverlayPanel {

    private final NpcKillerScript script;
    private final NpcKillerConfig config;

    @Inject
    NpcKillerOverlay(NpcKillerPlugin plugin, NpcKillerScript script, NpcKillerConfig config) {
        super(plugin);
        this.script = script;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
        setPreferredSize(new Dimension(ComponentConstants.STANDARD_WIDTH, 160));
        setNaughty();
        setDragTargetable(true);
        setLayer(OverlayLayer.UNDER_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.getChildren().clear();
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("NPC Killer")
                    .color(Color.GREEN)
                    .build());

            NpcKillerScript.State state = script.getCurrentState();
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("State:")
                    .right(state != null ? state.name().replace('_', ' ') : "starting")
                    .rightColor(state == NpcKillerScript.State.RECLAIMING_GEAR
                            || state == NpcKillerScript.State.LEAVING_DEATHS_OFFICE ? Color.RED : Color.WHITE)
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Target:")
                    .right(config.npcName())
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Attacks:")
                    .right(Integer.toString(script.getAttackCount()))
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Looted:")
                    .right(Integer.toString(script.getLootCount()))
                    .build());

            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Bank trips:")
                    .right(Integer.toString(script.getBankTrips()))
                    .build());

            if (script.getDeathCount() > 0) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Deaths:")
                        .right(Integer.toString(script.getDeathCount()))
                        .rightColor(Color.RED)
                        .build());
            }

            if (script.getKillArea() != null) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Area:")
                        .right(script.getKillArea().getX() + ", " + script.getKillArea().getY())
                        .build());
            }
        } catch (Exception ex) {
            log.warn("[NpcKiller] Overlay render error", ex);
        }
        return super.render(graphics);
    }
}
