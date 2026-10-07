package com.voidmachine.core.script;

import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.core.timeline.Phase;
import com.voidmachine.core.timeline.VariantPicker;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The look and sound of a machine: per-phase scripts, reveal scripts per tier and the legendary
 * jackpot variants.
 */
public record Theme(String id, String bossbarColor, Map<Phase, Script> phases, Map<OutcomeTier, Script> reveals,
                    List<Jackpot> jackpots, Script ambientIdle, Script voidEvent) {

    public record Jackpot(String id, double weight, int hold, String bossbarColor, Script script) {
    }

    public Script phase(Phase phase) {
        return phases.getOrDefault(phase, Script.EMPTY);
    }

    public Script reveal(OutcomeTier tier) {
        return reveals.getOrDefault(tier, Script.EMPTY);
    }

    public Optional<Jackpot> jackpot(String id) {
        return jackpots.stream().filter(j -> j.id().equals(id)).findFirst();
    }

    public int longestJackpotHold() {
        return jackpots.stream().mapToInt(Jackpot::hold).max().orElse(0);
    }

    public List<VariantPicker.Option> jackpotOptions() {
        return jackpots.stream().map(j -> new VariantPicker.Option(j.id(), j.weight())).toList();
    }
}
