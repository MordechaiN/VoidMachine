package com.voidmachine.core.timeline;

import com.voidmachine.core.outcome.RandomSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Weighted choice of a jackpot variant that avoids showing the same variant twice in a row on one
 * machine (when another variant exists). Main-thread confined.
 */
public final class VariantPicker {

    public record Option(String id, double weight) {
    }

    private final Map<String, String> lastByMachine = new HashMap<>();

    public String pick(String machineId, List<Option> options, RandomSource rnd) {
        if (options.isEmpty()) throw new IllegalArgumentException("no jackpot variants configured");
        String last = lastByMachine.get(machineId);
        List<Option> pool = options.size() > 1
                ? options.stream().filter(o -> !o.id().equals(last) && o.weight() > 0).toList()
                : options;
        if (pool.isEmpty()) pool = options;
        double total = 0;
        for (Option o : pool) total += Math.max(0, o.weight());
        String chosen = pool.getFirst().id();
        if (total > 0) {
            double r = rnd.nextDouble() * total;
            for (Option o : pool) {
                r -= Math.max(0, o.weight());
                if (r < 0) {
                    chosen = o.id();
                    break;
                }
            }
        }
        lastByMachine.put(machineId, chosen);
        return chosen;
    }

    public void forgetMachine(String machineId) {
        lastByMachine.remove(machineId);
    }
}
