package com.voidmachine.paper.i18n;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Detects Bedrock players (Geyser). Uses the Floodgate API, then the Geyser API, both optional and
 * accessed reflectively so VoidMachine has no hard dependency; falls back to Floodgate's default UUID
 * scheme (most significant bits zero).
 *
 * <p>Gameplay never depends on this — only text direction for Hebrew/Arabic does.</p>
 */
public final class BedrockDetector {

    private final Map<UUID, Boolean> cache = new ConcurrentHashMap<>();
    private Object floodgateApi;
    private Method floodgateCheck;
    private Object geyserApi;
    private Method geyserCheck;
    private String source = "uuid-heuristic";

    public BedrockDetector(Logger logger) {
        try {
            if (Bukkit.getPluginManager().getPlugin("floodgate") != null) {
                Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgateApi = api.getMethod("getInstance").invoke(null);
                floodgateCheck = api.getMethod("isFloodgatePlayer", UUID.class);
                source = "floodgate";
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.fine("Floodgate API unavailable: " + e);
        }
        if (floodgateCheck == null) {
            try {
                if (Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null) {
                    Class<?> api = Class.forName("org.geysermc.geyser.api.GeyserApi");
                    geyserApi = api.getMethod("api").invoke(null);
                    geyserCheck = api.getMethod("isBedrockPlayer", UUID.class);
                    source = "geyser";
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                logger.fine("Geyser API unavailable: " + e);
            }
        }
    }

    public boolean isBedrock(Player player) {
        return cache.computeIfAbsent(player.getUniqueId(), this::detect);
    }

    private boolean detect(UUID id) {
        try {
            if (floodgateCheck != null) return (boolean) floodgateCheck.invoke(floodgateApi, id);
            if (geyserCheck != null) return (boolean) geyserCheck.invoke(geyserApi, id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall through to the heuristic
        }
        return id.getMostSignificantBits() == 0L;
    }

    public void forget(UUID id) {
        cache.remove(id);
    }

    /** Which detection method is active (for diagnostics). */
    public String source() {
        return source;
    }
}
