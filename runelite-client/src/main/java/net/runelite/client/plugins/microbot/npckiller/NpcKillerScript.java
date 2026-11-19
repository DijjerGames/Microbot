package net.runelite.client.plugins.microbot.npckiller;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.annotations.Varp;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ObjectID1;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.statemachine.StateMachineScript;
import net.runelite.client.plugins.microbot.statemachine.Transition;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Kills an NPC, loots configured drops and banks them when the inventory is full.
 * <p>
 * The tile the player is standing on when the plugin starts becomes the "kill area" anchor.
 * The script never wanders further than {@code searchRadius} from it, and walks back to it
 * after every bank trip or death.
 * <p>
 * States: KILLING → LOOTING → WALK_TO_BANK → BANKING → RETURN_TO_AREA → KILLING,
 * with RECOVERING_FROM_DEATH pre-empting everything else.
 * <p>
 * Live state: {@code GET /debug/snapshot?script=NpcKillerScript}
 */
@Slf4j
public class NpcKillerScript extends StateMachineScript<NpcKillerScript.State> {

    public enum State {
        KILLING,
        LOOTING,
        WALK_TO_BANK,
        BANKING,
        RETURN_TO_AREA,
        RECLAIMING_GEAR,
        LEAVING_DEATHS_OFFICE
    }

    /** Set to 1 while Death is holding items for the player. Same varp the engine's DeathEvent uses. */
    @Varp
    private static final int DEATH_COUNTER_VARP = 4517;
    /** Region id of Death's Office. */
    private static final int DEATH_DOMAIN_REGION_ID = 12633;
    private static final int DEATHS_PORTAL = ObjectID1.DEATH_OFFICE_EXITPORTAL;
    private static final String DEATH_NPC_NAME = "Death";
    /** Give up on reclaiming after this long so a dialogue we don't understand cannot trap the script. */
    private static final long RECLAIM_TIMEOUT_MS = 45_000;

    /**
     * Dialogue options that lead to the item-reclaim interface. Matched as substrings, most
     * specific first, because Death's wording differs between the first deaths and later ones.
     */
    private static final String[] RECLAIM_DIALOGUE_OPTIONS = {
            "Can I have my items back",
            "items back",
            "my items",
            "belongings",
            "retrieve",
            "collect"
    };

    /** Menu verbs that equip an item, in the order we prefer them. */
    private static final String[] EQUIP_ACTIONS = {"Wield", "Wear", "Equip"};

    private NpcKillerConfig config;

    /** Anchor tile for the kill area — captured on the first tick after login. */
    @Getter
    private WorldPoint killArea;

    private List<String> lootNames = Collections.emptyList();

    /** True once the current death has been counted, so one death is not counted twice. */
    private boolean deathAcknowledged;
    /** True once the reclaim step has finished or been ruled out for the current death. */
    private boolean reclaimAttempted;
    /** True when items were still sitting with Death after clicking Take-all — i.e. the fee was unaffordable. */
    private boolean reclaimFailed;
    private long reclaimStartedAt;

    /** Names of the gear worn while killing, so it can be put back on after a reclaim. */
    private List<String> gearSnapshot = Collections.emptyList();

    @Getter
    private int attackCount;
    @Getter
    private int lootCount;
    @Getter
    private int bankTrips;
    @Getter
    private int deathCount;

    // --- State machine definition ---

    @Override
    protected State initialState() {
        return State.KILLING;
    }

    @Override
    protected List<Transition<State>> defineTransitions() {
        List<Transition<State>> transitions = new ArrayList<>();

        // Highest priority: dying pre-empts whatever we were doing.
        for (State from : State.values()) {
            if (from == State.RECLAIMING_GEAR || from == State.LEAVING_DEATHS_OFFICE) continue;
            transitions.add(Transition.<State>from(from)
                    .when(this::hasDied, "hasDied()")
                    .because("Player died")
                    .goTo(State.RECLAIMING_GEAR));
        }

        // Gear is back (or was never reclaimable) — head for the exit portal.
        transitions.add(Transition.<State>from(State.RECLAIMING_GEAR)
                .when(() -> reclaimAttempted && inDeathsOffice(), "reclaimAttempted && inDeathsOffice()")
                .because("Reclaim finished, leaving Death's Office")
                .goTo(State.LEAVING_DEATHS_OFFICE));

        // Died without an office visit, so there was nothing to reclaim here.
        transitions.add(Transition.<State>from(State.RECLAIMING_GEAR)
                .when(() -> reclaimAttempted && !inDeathsOffice() && shouldContinueAfterDeath(),
                        "reclaimAttempted && !inDeathsOffice() && shouldContinueAfterDeath()")
                .because("Not in Death's Office, walking back")
                .goTo(State.RETURN_TO_AREA));

        transitions.add(Transition.<State>from(State.LEAVING_DEATHS_OFFICE)
                .when(() -> !inDeathsOffice() && shouldContinueAfterDeath(),
                        "!inDeathsOffice() && shouldContinueAfterDeath()")
                .because("Out of Death's Office, walking back")
                .goTo(State.RETURN_TO_AREA));

        // A full inventory wins over fighting or looting.
        transitions.add(Transition.<State>from(State.KILLING)
                .when(Rs2Inventory::isFull, "Rs2Inventory.isFull()")
                .because("Inventory full, heading to the bank")
                .goTo(State.WALK_TO_BANK));

        transitions.add(Transition.<State>from(State.LOOTING)
                .when(Rs2Inventory::isFull, "Rs2Inventory.isFull()")
                .because("Inventory full, heading to the bank")
                .goTo(State.WALK_TO_BANK));

        // Dragged, teleported or respawned far away — regroup before doing anything else.
        transitions.add(Transition.<State>from(State.KILLING)
                .when(this::farFromKillArea, "farFromKillArea()")
                .because("Too far from the kill area")
                .goTo(State.RETURN_TO_AREA));

        // Loot on the floor takes priority over starting another fight.
        transitions.add(Transition.<State>from(State.KILLING)
                .when(this::lootIsNearby, "lootIsNearby()")
                .because("Loot dropped nearby")
                .goTo(State.LOOTING));

        transitions.add(Transition.<State>from(State.LOOTING)
                .when(() -> !lootIsNearby(), "!lootIsNearby()")
                .because("Nothing left to pick up")
                .goTo(State.KILLING));

        transitions.add(Transition.<State>from(State.WALK_TO_BANK)
                .when(this::atBank, "atBank()")
                .because("Arrived at the bank")
                .goTo(State.BANKING));

        transitions.add(Transition.<State>from(State.BANKING)
                .when(() -> !hasLoot() && !Rs2Bank.isOpen(), "!hasLoot() && !Rs2Bank.isOpen()")
                .because("Loot deposited, returning to the kill area")
                .goTo(State.RETURN_TO_AREA));

        transitions.add(Transition.<State>from(State.RETURN_TO_AREA)
                .when(this::atKillArea, "atKillArea()")
                .because("Back at the kill area")
                .goTo(State.KILLING));

        return transitions;
    }

    @Override
    protected void onTransition(State from, State to, String reason) {
        super.onTransition(from, to, reason);
        if (to == State.RECLAIMING_GEAR) {
            deathAcknowledged = true;
            reclaimAttempted = false;
            reclaimFailed = false;
            reclaimStartedAt = System.currentTimeMillis();
            deathCount++;
            log.warn("[NpcKiller] Death #{} detected — gear snapshot is {}", deathCount, gearSnapshot);
        }
        if (to == State.KILLING) {
            // Remember what we fight in, so a reclaim can put it back on. Never overwrite a good
            // snapshot with the empty equipment we have immediately after dying.
            List<String> worn = Rs2Equipment.all()
                    .map(Rs2ItemModel::getName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!worn.isEmpty()) {
                gearSnapshot = worn;
            }
        }
    }

    @Override
    protected void onState(State state) {
        // Death is cleared once the player has collected their items back from Death.
        if (deathAcknowledged && Microbot.getVarbitPlayerValue(DEATH_COUNTER_VARP) == 0) {
            deathAcknowledged = false;
        }

        if (killArea == null) {
            killArea = Rs2Player.getWorldLocation();
            if (killArea == null) {
                return;
            }
            log.info("[NpcKiller] Kill area anchored at {}", killArea);
        }

        switch (state) {
            case KILLING:
                eatIfNeeded();
                doKill();
                break;
            case LOOTING:
                eatIfNeeded();
                doLoot();
                break;
            case WALK_TO_BANK:
                Rs2Bank.walkToBank(resolveBank());
                break;
            case BANKING:
                doBank();
                break;
            case RETURN_TO_AREA:
                Rs2Walker.walkTo(killArea, config.searchRadius() / 2);
                break;
            case RECLAIMING_GEAR:
                doReclaimGear();
                break;
            case LEAVING_DEATHS_OFFICE:
                doLeaveDeathsOffice();
                break;
        }
    }

    // --- Guards (pure, non-blocking) ---

    /**
     * Standing in Death's Office always counts. Otherwise the death varp catches deaths that
     * respawn straight at the respawn point, once per death.
     */
    private boolean hasDied() {
        if (inDeathsOffice()) {
            return true;
        }
        return !deathAcknowledged && Microbot.getVarbitPlayerValue(DEATH_COUNTER_VARP) == 1;
    }

    private boolean inDeathsOffice() {
        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        return playerLocation != null && playerLocation.getRegionID() == DEATH_DOMAIN_REGION_ID;
    }

    private boolean shouldContinueAfterDeath() {
        if (config.stopOnDeath()) {
            return false;
        }
        // Carrying on without the gear we could not pay for would just feed another death.
        if (reclaimFailed) {
            return false;
        }
        return config.maxDeaths() <= 0 || deathCount < config.maxDeaths();
    }

    private boolean lootIsNearby() {
        if (killArea == null || lootNames.isEmpty() || Rs2Inventory.isFull()) {
            return false;
        }
        return findLoot() != null;
    }

    private boolean hasLoot() {
        // No loot names configured means "bank everything", so anything in the inventory counts.
        if (lootNames.isEmpty()) {
            return !Rs2Inventory.isEmpty();
        }
        return lootNames.stream().anyMatch(name -> Rs2Inventory.hasItem(name, true));
    }

    private boolean atBank() {
        if (Rs2Bank.isOpen()) {
            return true;
        }
        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        return playerLocation != null && resolveBank().getWorldPoint().distanceTo(playerLocation) <= 8;
    }

    private boolean atKillArea() {
        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        return killArea != null && playerLocation != null
                && killArea.distanceTo(playerLocation) <= config.searchRadius();
    }

    /** Deliberately looser than {@link #atKillArea()} so the two cannot flap against each other. */
    private boolean farFromKillArea() {
        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        return killArea != null && playerLocation != null
                && killArea.distanceTo(playerLocation) > config.searchRadius() * 2;
    }

    // --- State actions ---

    private void doKill() {
        if (Rs2Player.isInCombat() || Rs2Player.isInteracting()) {
            return;
        }

        Rs2NpcModel target = Microbot.getRs2NpcCache().query()
                .withName(config.npcName())
                .where(npc -> npc.getHealthRatio() != 0)
                .where(npc -> !npc.isInteracting())
                .within(killArea, config.searchRadius())
                .nearestReachable();

        if (target == null) {
            // Drifted away from the field, or everything is already tagged — regroup and wait.
            if (!atKillArea()) {
                Rs2Walker.walkTo(killArea, config.searchRadius() / 2);
            }
            return;
        }

        if (target.click("Attack")) {
            attackCount++;
            sleepUntil(Rs2Player::isInCombat, 3000);
        }
    }

    private void doLoot() {
        Rs2TileItemModel loot = findLoot();
        if (loot == null) {
            return;
        }

        String name = loot.getName();
        if (loot.pickup()) {
            if (Rs2Inventory.waitForInventoryChanges(5000)) {
                lootCount++;
                log.debug("[NpcKiller] Picked up {}", name);
            }
            sleep(Rs2Random.between(200, 500));
        }
    }

    private void doBank() {
        if (!Rs2Bank.isOpen()) {
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 5000);
            return;
        }

        if (lootNames.isEmpty()) {
            Rs2Bank.depositAll();
        } else {
            // Deposit only the configured loot so food and gear stay in the inventory.
            for (String name : lootNames) {
                if (Rs2Inventory.hasItem(name, true)) {
                    Rs2Bank.depositAll(name);
                    sleep(Rs2Random.between(200, 400));
                }
            }
        }

        sleepUntil(() -> !hasLoot(), 3000);
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 3000);
        bankTrips++;
    }

    /**
     * Collects the items Death is holding and puts the gear back on.
     * <p>
     * This plugin takes ownership of Death's Office from the engine's {@code DeathEvent} while it
     * is enabled (see {@link NpcKillerPlugin}), otherwise that event clicks straight through to the
     * exit portal and we never get the chance to reclaim anything.
     */
    private void doReclaimGear() {
        // Nothing to reclaim from here, or the user turned it off.
        if (!inDeathsOffice() || !config.reclaimGear()) {
            reclaimAttempted = true;
            return;
        }

        if (System.currentTimeMillis() - reclaimStartedAt > RECLAIM_TIMEOUT_MS) {
            log.warn("[NpcKiller] Gave up reclaiming after {}ms, leaving without the gear", RECLAIM_TIMEOUT_MS);
            reclaimAttempted = true;
            return;
        }

        // The reclaim interface is up — take everything, then check nothing was left behind.
        if (Rs2Widget.isWidgetVisible(InterfaceID.DeathOffice.ITEMS_CONTAINER)) {
            if (deathIsHoldingItems()) {
                Rs2Widget.clickWidget(InterfaceID.DeathOffice.TAKEALL);
                Rs2Inventory.waitForInventoryChanges(5000);
                sleep(Rs2Random.between(400, 800));
            }

            if (deathIsHoldingItems()) {
                // Take-all leaves anything we could not pay the fee for.
                reclaimFailed = true;
                log.warn("[NpcKiller] Items still held by Death after Take-all — the reclaim fee is unaffordable");
            } else {
                log.info("[NpcKiller] Reclaimed items from Death");
            }

            reEquipGear();
            reclaimAttempted = true;
            return;
        }

        // Talk our way to the interface.
        if (Rs2Dialogue.isInDialogue()) {
            if (Rs2Dialogue.hasSelectAnOption()) {
                if (Rs2Dialogue.clickOption(false, RECLAIM_DIALOGUE_OPTIONS)) {
                    sleep(Rs2Random.between(600, 1000));
                    return;
                }
                // Death gates the reclaim option behind his introduction on the first few deaths.
                // Work through the options we have not exhausted yet until it appears.
                if (clickFirstUnreadDialogueOption()) {
                    sleep(Rs2Random.between(600, 1000));
                }
                return;
            }
            if (Rs2Dialogue.hasContinue()) {
                Rs2Dialogue.clickContinue();
                sleep(Rs2Random.between(400, 800));
            }
            return;
        }

        Rs2NpcModel death = Microbot.getRs2NpcCache().query()
                .withName(DEATH_NPC_NAME)
                .nearest();

        if (death == null) {
            log.warn("[NpcKiller] Could not find '{}' in Death's Office", DEATH_NPC_NAME);
            reclaimAttempted = true;
            return;
        }

        // Fall back to Death's first menu action rather than insisting on a verb he may not expose.
        if (death.click("Talk-to") || death.click()) {
            Rs2Dialogue.sleepUntilInDialogue();
        }
    }

    /** Picks the first dialogue option the player has not already been through (unstruck text). */
    private boolean clickFirstUnreadDialogueOption() {
        List<Widget> options = Rs2Dialogue.getDialogueOptions();
        for (int i = 0; i < options.size(); i++) {
            String text = options.get(i).getText();
            if (text != null && !text.matches("<str>.*</str>")) {
                return Rs2Dialogue.keyPressForDialogueOption(i + 1);
            }
        }
        return false;
    }

    /**
     * Leaves through the exit portal, clearing any dialogue first, and stops the script here if the
     * death limit is reached or the reclaim fee could not be paid.
     */
    private void doLeaveDeathsOffice() {
        if (inDeathsOffice()) {
            if (Rs2Dialogue.isInDialogue()) {
                if (Rs2Dialogue.hasContinue()) {
                    Rs2Dialogue.clickContinue();
                }
                return;
            }
            if (Rs2Player.isMoving()) {
                return;
            }
            Microbot.getRs2TileObjectCache().query().interact(DEATHS_PORTAL, "use");
            sleepUntil(() -> !inDeathsOffice(), 10000);
            return;
        }

        // Out of the office but not allowed to carry on — stop here rather than dying again.
        if (!shouldContinueAfterDeath()) {
            log.warn("[NpcKiller] Stopping after {} death(s) — stopOnDeath={}, maxDeaths={}, reclaimFailed={}",
                    deathCount, config.stopOnDeath(), config.maxDeaths(), reclaimFailed);
            Microbot.status = "NPC Killer stopped after death";
            shutdown();
        }
    }

    /** True while Death's reclaim interface still lists at least one item. */
    private boolean deathIsHoldingItems() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget items = Microbot.getClient().getWidget(InterfaceID.DeathOffice.ITEMS);
            if (items == null) {
                return false;
            }
            Widget[] children = items.getDynamicChildren();
            if (children == null) {
                return false;
            }
            return Arrays.stream(children).anyMatch(child -> child.getItemId() > 0);
        }).orElse(false);
    }

    /**
     * Puts the pre-death gear back on. The equip verb is read from each item rather than hardcoded,
     * because armour uses "Wear" while weapons use "Wield".
     */
    private void reEquipGear() {
        if (gearSnapshot.isEmpty()) {
            return;
        }

        for (String name : gearSnapshot) {
            if (Rs2Equipment.isWearing(name, true)) {
                continue;
            }
            Rs2ItemModel item = Rs2Inventory.get(name);
            if (item == null) {
                log.warn("[NpcKiller] '{}' was not reclaimed, cannot re-equip it", name);
                continue;
            }

            String action = Arrays.stream(item.getInventoryActions())
                    .filter(Objects::nonNull)
                    .filter(a -> Arrays.stream(EQUIP_ACTIONS).anyMatch(a::equalsIgnoreCase))
                    .findFirst()
                    .orElse(null);

            if (action == null) {
                log.warn("[NpcKiller] '{}' exposes no equip action, skipping it", name);
                continue;
            }

            Rs2Inventory.interact(item, action);
            sleep(Rs2Random.between(200, 400));
        }
    }

    // --- Helpers ---

    private Rs2TileItemModel findLoot() {
        return Microbot.getRs2TileItemCache().query()
                .withNames(lootNames.toArray(new String[0]))
                .where(Rs2TileItemModel::isLootAble)
                .within(killArea, config.searchRadius())
                .nearestReachable();
    }

    private BankLocation resolveBank() {
        if (!config.useNearestBank()) {
            return config.bankLocation();
        }
        BankLocation nearest = Rs2Bank.getNearestBank();
        return nearest != null ? nearest : config.bankLocation();
    }

    private void eatIfNeeded() {
        if (config.eatFood()) {
            Rs2Player.eatAt(config.eatAtHealthPercent());
        }
    }

    // --- Lifecycle ---

    public boolean run(NpcKillerConfig config) {
        this.config = config;
        this.lootNames = parseLootNames(config.lootItems());
        this.killArea = null;
        this.deathAcknowledged = false;
        this.reclaimAttempted = false;
        this.reclaimFailed = false;
        this.gearSnapshot = Collections.emptyList();
        this.attackCount = 0;
        this.lootCount = 0;
        this.bankTrips = 0;
        this.deathCount = 0;

        log.info("[NpcKiller] Starting — target='{}', loot={}", config.npcName(), lootNames);

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                step();
            } catch (Exception ex) {
                log.error("[NpcKiller] Unexpected error in scheduled loop", ex);
            }
        }, 0, config.tickDelay(), TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        killArea = null;
        log.info("[NpcKiller] Stopped — {} attacks, {} items looted, {} bank trips, {} deaths",
                attackCount, lootCount, bankTrips, deathCount);
    }

    private static List<String> parseLootNames(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
