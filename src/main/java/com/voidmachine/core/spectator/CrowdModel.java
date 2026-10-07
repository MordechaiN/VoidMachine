package com.voidmachine.core.spectator;

/**
 * The machine feeds on attention: the more onlookers, the more intense the presentation. Purely
 * cosmetic — odds never depend on who is watching.
 *
 * @param minForBonus  onlookers (excluding the owner) needed before intensity rises
 * @param maxIntensity cap on the intensity factor (1.0 = no crowd bonus)
 * @param fullAt       onlookers at which {@code maxIntensity} is reached
 */
public record CrowdModel(int minForBonus, double maxIntensity, int fullAt) {

    public CrowdModel {
        if (minForBonus < 1) throw new IllegalArgumentException("min-for-bonus must be >= 1");
        if (maxIntensity < 1.0 || maxIntensity > 3.0) throw new IllegalArgumentException("max-intensity must be between 1.0 and 3.0");
        if (fullAt < minForBonus) throw new IllegalArgumentException("full-at must be >= min-for-bonus");
    }

    /** Intensity factor in {@code [1, maxIntensity]}, linear between {@code minForBonus} and {@code fullAt}. */
    public double intensity(int onlookers) {
        if (onlookers < minForBonus) return 1.0;
        if (fullAt == minForBonus) return maxIntensity;
        double t = Math.min(1.0, (double) (onlookers - minForBonus + 1) / (fullAt - minForBonus + 1));
        return 1.0 + (maxIntensity - 1.0) * t;
    }

    public boolean isCrowd(int onlookers) {
        return onlookers >= minForBonus;
    }
}
