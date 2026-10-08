package com.voidmachine.core.config;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Game registry for tests, built from the real Paper 26.2 API: every vanilla sound event key (from
 * the generated {@code SoundEventKeys}) and every particle with its data type. Materials use a small
 * fixed list.
 */
public final class TestRegistry implements GameRegistry {

    public static final TestRegistry INSTANCE = new TestRegistry();

    private final Set<String> sounds = new HashSet<>();
    private final Map<String, ParticleData> particles = new HashMap<>();
    private static final Set<String> BLOCKS = Set.of("RESPAWN_ANCHOR", "LODESTONE", "CRYING_OBSIDIAN", "STONE");
    private static final Set<String> ITEMS = Set.of("WRITTEN_BOOK", "WRITABLE_BOOK", "KNOWLEDGE_BOOK", "FILLED_MAP",
            "BUNDLE", "SHULKER_BOX", "DIAMOND", "STONE", "RESPAWN_ANCHOR", "WHITE_BUNDLE", "WHITE_SHULKER_BOX",
            "ORANGE_BUNDLE", "ORANGE_SHULKER_BOX", "MAGENTA_BUNDLE", "MAGENTA_SHULKER_BOX", "LIGHT_BLUE_BUNDLE",
            "LIGHT_BLUE_SHULKER_BOX", "YELLOW_BUNDLE", "YELLOW_SHULKER_BOX", "LIME_BUNDLE", "LIME_SHULKER_BOX",
            "PINK_BUNDLE", "PINK_SHULKER_BOX", "GRAY_BUNDLE", "GRAY_SHULKER_BOX", "LIGHT_GRAY_BUNDLE",
            "LIGHT_GRAY_SHULKER_BOX", "CYAN_BUNDLE", "CYAN_SHULKER_BOX", "PURPLE_BUNDLE", "PURPLE_SHULKER_BOX",
            "BLUE_BUNDLE", "BLUE_SHULKER_BOX", "BROWN_BUNDLE", "BROWN_SHULKER_BOX", "GREEN_BUNDLE", "GREEN_SHULKER_BOX",
            "RED_BUNDLE", "RED_SHULKER_BOX", "BLACK_BUNDLE", "BLACK_SHULKER_BOX");

    private TestRegistry() {
        try {
            Class<?> keys = Class.forName("io.papermc.paper.registry.keys.SoundEventKeys");
            for (Field f : keys.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                Object v = f.get(null);
                if (v instanceof net.kyori.adventure.key.Keyed k) sounds.add(k.key().asString());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        for (Particle p : Particle.values()) {
            Class<?> t = p.getDataType();
            ParticleData d;
            if (t == Void.class) d = ParticleData.NONE;
            else if (t == Particle.DustOptions.class) d = ParticleData.DUST;
            else if (t == Particle.DustTransition.class) d = ParticleData.DUST_TRANSITION;
            else if (t == Color.class) d = ParticleData.COLOR;
            else if (t == ItemStack.class) d = ParticleData.ITEM;
            else if (t == Float.class) d = ParticleData.FLOAT;
            else if (t == Integer.class) d = ParticleData.INTEGER;
            else d = ParticleData.UNSUPPORTED;
            particles.put(p.getKey().asString(), d);
        }
        if (sounds.size() < 1000) throw new IllegalStateException("expected the full vanilla sound list, got " + sounds.size());
    }

    @Override
    public boolean isBlock(String material) {
        return BLOCKS.contains(material);
    }

    @Override
    public boolean isItem(String material) {
        return ITEMS.contains(material);
    }

    @Override
    public boolean soundExists(String key) {
        return sounds.contains(key);
    }

    @Override
    public Optional<ParticleData> particle(String key) {
        return Optional.ofNullable(particles.get(key));
    }
}
