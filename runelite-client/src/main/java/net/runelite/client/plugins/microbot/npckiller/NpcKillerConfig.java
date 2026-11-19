package net.runelite.client.plugins.microbot.npckiller;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.plugins.microbot.util.bank.enums.BankLocation;

@ConfigGroup("npckiller")
public interface NpcKillerConfig extends Config {

    @ConfigItem(
            keyName = "npcName",
            name = "NPC name",
            description = "Exact name of the NPC to attack",
            position = 0
    )
    default String npcName() {
        return "Cow";
    }

    @ConfigItem(
            keyName = "lootItems",
            name = "Loot items",
            description = "Comma separated item names to pick up",
            position = 1
    )
    default String lootItems() {
        return "Cowhide";
    }

    @ConfigItem(
            keyName = "searchRadius",
            name = "Search radius",
            description = "How far from the start tile to look for NPCs and loot",
            position = 2
    )
    default int searchRadius() {
        return 12;
    }

    @ConfigItem(
            keyName = "useNearestBank",
            name = "Use nearest bank",
            description = "Bank at whichever bank is closest instead of the one selected below",
            position = 3
    )
    default boolean useNearestBank() {
        return true;
    }

    @ConfigItem(
            keyName = "bankLocation",
            name = "Bank location",
            description = "Bank to use when 'Use nearest bank' is off",
            position = 4
    )
    default BankLocation bankLocation() {
        return BankLocation.LUMBRIDGE_TOP;
    }

    @ConfigItem(
            keyName = "eatFood",
            name = "Eat food",
            description = "Eat food from the inventory when health drops below the threshold",
            position = 5
    )
    default boolean eatFood() {
        return true;
    }

    @ConfigItem(
            keyName = "eatAtHealthPercent",
            name = "Eat at health %",
            description = "Eat when health drops below this percentage",
            position = 6
    )
    default int eatAtHealthPercent() {
        return 50;
    }

    @ConfigItem(
            keyName = "reclaimGear",
            name = "Reclaim gear on death",
            description = "Collect items back from Death and re-equip them before leaving Death's Office",
            position = 7
    )
    default boolean reclaimGear() {
        return true;
    }

    @ConfigItem(
            keyName = "stopOnDeath",
            name = "Stop on death",
            description = "Stop the script after leaving Death's Office instead of walking back",
            position = 8
    )
    default boolean stopOnDeath() {
        return false;
    }

    @ConfigItem(
            keyName = "maxDeaths",
            name = "Max deaths",
            description = "Stop the script after this many deaths. 0 disables the limit",
            position = 9
    )
    default int maxDeaths() {
        return 3;
    }

    @ConfigItem(
            keyName = "tickDelay",
            name = "Tick delay (ms)",
            description = "Delay between state machine ticks in milliseconds",
            position = 10
    )
    default int tickDelay() {
        return 600;
    }
}
