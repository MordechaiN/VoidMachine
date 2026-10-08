package com.voidmachine.paper.config;

import com.voidmachine.core.config.GameRegistry;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/** {@link GameRegistry} backed by the running server's registries. */
public final class PaperGameRegistry implements GameRegistry {

    @Override
    public boolean isBlock(String material) {
        Material m = Material.matchMaterial(material);
        return m != null && m.isBlock() && !m.isAir();
    }

    @Override
    public boolean isItem(String material) {
        Material m = Material.matchMaterial(material);
        return m != null && m.isItem();
    }

    @Override
    public boolean soundExists(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        return k != null && Registry.SOUND_EVENT.get(k) != null;
    }

    @Override
    public Optional<ParticleData> particle(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        if (k == null) return Optional.empty();
        Particle p = Registry.PARTICLE_TYPE.get(k);
        if (p == null) return Optional.empty();
        Class<?> t = p.getDataType();
        if (t == Void.class) return Optional.of(ParticleData.NONE);
        if (t == Particle.DustOptions.class) return Optional.of(ParticleData.DUST);
        if (t == Particle.DustTransition.class) return Optional.of(ParticleData.DUST_TRANSITION);
        if (t == Color.class) return Optional.of(ParticleData.COLOR);
        if (t == ItemStack.class) return Optional.of(ParticleData.ITEM);
        if (t == Float.class) return Optional.of(ParticleData.FLOAT);
        if (t == Integer.class) return Optional.of(ParticleData.INTEGER);
        return Optional.of(ParticleData.UNSUPPORTED);
    }
}
