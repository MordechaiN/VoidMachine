package com.voidmachine.core.config;

import com.voidmachine.core.outcome.OutcomeDefinition;
import com.voidmachine.core.outcome.OutcomeTable;
import com.voidmachine.core.spectator.CrowdModel;
import com.voidmachine.core.spectator.SpectatorTier;
import com.voidmachine.core.timeline.FakeoutPlan;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validated, immutable contents of {@code config.yml}. Built only by {@link SettingsLoader}.
 * Active rituals keep the settings they started with, so a reload never changes a ritual mid-flight.
 */
public record Settings(
        Language language,
        Machines machines,
        Offering offering,
        Limits limits,
        Cooldowns cooldowns,
        Map<String, OutcomeDefinition> outcomes,
        Map<String, Profile> profiles,
        Presentation presentation,
        Spectators spectators,
        Ambient ambient,
        Delivery delivery,
        Logging logging,
        Stats stats
) {

    public static final int CONFIG_VERSION = 2;

    public record Language(String defaultLanguage, boolean useClientLanguage, boolean bedrockRtlFix) {
    }

    public record Machines(String coreBlock, int maxMachines, Set<String> allowedWorlds, Set<String> blockedWorlds,
                           boolean allowCreative, int interactCooldownMillis) {
        public boolean worldAllowed(String world) {
            if (blockedWorlds.contains(world)) return false;
            return allowedWorlds.isEmpty() || allowedWorlds.contains(world);
        }
    }

    public record Offering(int maxAmount, Set<String> blockedMaterials, boolean blockFilledContainers,
                           boolean blockUnstackable, Set<String> blockedPdcKeys) {
    }

    public record Limits(int maxRewardAmount, int maxActiveRituals, int maxUnclaimedPerPlayer,
                         int maxRitualDurationTicks, int maxSpectatorsPerRitual, int maxParticlesPerTick,
                         int maxPacketsPerTick, int maxDisplayEntitiesPerRitual) {
    }

    public record Cooldowns(int playerSeconds, int machineSeconds) {
    }

    /**
     * A machine profile: its odds, choreography and optional access permission.
     *
     * @param permission extra permission required to use machines with this profile ("" = none)
     */
    public record Profile(String id, OutcomeTable table, String theme, String pacing, String permission) {
    }

    public enum AnnounceScope {
        NONE, AREA, SERVER
    }

    public record Presentation(boolean ritualChamberGui, boolean tetherPlayer, double tetherRadius,
                               boolean spectatorBossbar, boolean narrateToSpectators, Fakeouts fakeouts,
                               Map<String, AnnounceScope> announce, int serverAnnounceCooldownSeconds) {
        public AnnounceScope announceFor(String tier) {
            return announce.getOrDefault(tier, AnnounceScope.NONE);
        }
    }

    public record Fakeouts(boolean enabled, double chance, int minRitualsBetweenPerPlayer, int globalCooldownSeconds,
                           Set<FakeoutPlan.Pattern> patterns) {
    }

    public record Spectators(SpectatorTier.Radii radii, int refreshTicks, CrowdModel crowd) {
    }

    public record Ambient(boolean enabled, double radius, boolean voidEvents, int voidEventMinMinutes,
                          int voidEventMaxMinutes) {
    }

    public enum Overflow {
        /** Keep what does not fit in the Void's custody (durable) and pay it out when there is room. */
        HOLD,
        /** Drop what does not fit at the player's feet, locked to them. */
        DROP
    }

    public record Delivery(Overflow overflow, int retrySeconds) {
    }

    public enum LogLevel {
        QUIET(0), INFO(1), DEBUG(2);

        private final int verbosity;

        LogLevel(int verbosity) {
            this.verbosity = verbosity;
        }

        /** Whether a message of level {@code message} is logged when this level is configured. */
        public boolean includes(LogLevel message) {
            return verbosity >= message.verbosity;
        }
    }

    public record Logging(LogLevel level, boolean auditEnabled, int auditRetentionDays) {
    }

    public record Stats(boolean enabled, int saveIntervalSeconds) {
    }

    /** Ids of every profile, for tab completion and validation of machines. */
    public List<String> profileIds() {
        return List.copyOf(profiles.keySet());
    }
}
