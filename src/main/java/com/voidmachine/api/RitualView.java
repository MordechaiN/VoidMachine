package com.voidmachine.api;

import com.voidmachine.core.outcome.OutcomeTier;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Immutable snapshot of a ritual for other plugins. Never exposes internal state.
 *
 * @param offering   a copy of the offered item (amount 1); safe to keep and modify
 * @param multiplier the outcome multiplier, e.g. {@code "2"} or {@code "0.5"}
 */
public record RitualView(UUID ritualId, UUID playerId, String playerName, MachineView machine, String profile,
                         ItemStack offering, int inputAmount, String outcomeId, OutcomeTier tier, String multiplier,
                         int rewardAmount) {

    public RitualView {
        offering = offering.clone();
    }

    @Override
    public ItemStack offering() {
        return offering.clone();
    }
}
