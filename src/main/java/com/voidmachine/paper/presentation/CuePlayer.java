package com.voidmachine.paper.presentation;

import com.destroystokyo.paper.ParticleBuilder;
import com.voidmachine.core.budget.EffectBudget;
import com.voidmachine.core.outcome.RandomSource;
import com.voidmachine.core.script.Cue;
import com.voidmachine.core.spectator.SpectatorTier;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns {@link Cue} data into effects for an audience, within the tick budget. Stateless apart from
 * a small particle lookup cache.
 */
public final class CuePlayer {

    /** What a cue needs from the ritual (or idle machine) it belongs to. */
    public interface Context {
        Location machineBlock();

        Audience audience();

        EffectBudget.Allowance allowance();

        RandomSource rng();

        /** Crowd intensity multiplier (1.0 = no crowd). */
        double intensity();

        ItemStack offering();

        /** Reward item for display cues (same item as the offering). */
        ItemStack reward();

        DisplayService.Handle display();

        /** Records players who were sent a fake charge state, so it can be restored. */
        java.util.Set<java.util.UUID> chargeViewers();
    }

    private static final Map<String, Particle> PARTICLES = new ConcurrentHashMap<>();

    private CuePlayer() {
    }

    /**
     * Plays one cue for the context's audience, within its effect allowance.
     *
     * @param progress progress of the current phase, 0..1 (drives "from..to" ramps)
     */
    public static void play(Cue cue, double progress, Context ctx) {
        // Always consume the chance roll so the presentation random stream does not depend on who watches.
        boolean fires = ctx.rng().nextDouble() < cue.chance();
        double jitterRoll = ctx.rng().nextDouble();
        if (!fires) return;
        switch (cue) {
            case Cue.Sound s -> sound(s, progress, jitterRoll, ctx);
            case Cue.Particle p -> particle(p, progress, ctx);
            case Cue.Lightning l -> lightning(ctx);
            case Cue.Display d -> {
                if (ctx.display() != null) ctx.display().apply(d.action(), ctx.reward());
            }
            case Cue.Charge c -> MachineVisuals.showCharge(ctx.machineBlock(), c.level(), ctx.audience().players(c.to()), ctx.chargeViewers());
        }
    }

    private static void sound(Cue.Sound s, double progress, double jitterRoll, Context ctx) {
        List<Audience.Member> receivers = new ArrayList<>();
        for (Audience.Member m : ctx.audience().members()) if (m.tier().hears(s.to())) receivers.add(m);
        if (receivers.isEmpty() || !ctx.allowance().sound(receivers.size())) return;
        double volume = s.volume().at(progress) * Math.min(1.5, ctx.intensity());
        double pitch = s.pitch().at(progress) + (jitterRoll * 2 - 1) * s.jitter();
        pitch = Math.max(0.5, Math.min(2.0, pitch));
        SoundCategory category = switch (s.channel()) {
            case MASTER -> SoundCategory.MASTER;
            case BLOCK -> SoundCategory.BLOCKS;
            case AMBIENT -> SoundCategory.AMBIENT;
            case HOSTILE -> SoundCategory.HOSTILE;
            case WEATHER -> SoundCategory.WEATHER;
        };
        Location at = ctx.machineBlock().clone().add(0.5, 0.8, 0.5);
        for (Audience.Member m : receivers) {
            float v = (float) (volume * m.tier().volumeShare());
            if (v <= 0) continue;
            Player p = m.player();
            Location where = s.to() == SpectatorTier.Audience.OWNER ? p.getLocation() : at;
            p.playSound(where, s.key(), category, v, (float) pitch);
        }
    }

    private static void particle(Cue.Particle c, double progress, Context ctx) {
        Particle particle = PARTICLES.computeIfAbsent(c.particle(), k -> {
            NamespacedKey key = NamespacedKey.fromString(k);
            return key == null ? null : Registry.PARTICLE_TYPE.get(key);
        });
        if (particle == null) return;
        List<Player> full = new ArrayList<>();
        List<Player> half = new ArrayList<>();
        for (Audience.Member m : ctx.audience().members()) {
            if (!m.tier().hears(c.to())) continue;
            double share = m.tier().particleShare();
            if (share >= 1.0) full.add(m.player());
            else if (share > 0) half.add(m.player());
        }
        if (full.isEmpty() && half.isEmpty()) return;
        int count = (int) Math.round(c.count().at(progress) * ctx.intensity());
        if (count <= 0) return;
        Object data = data(c, ctx.offering());
        List<Location> points = points(c, ctx);
        if (points.isEmpty()) return;
        for (Location point : points) {
            emit(particle, point, count, c, data, full, ctx);
            emit(particle, point, Math.max(1, count / 2), c, data, half, ctx);
        }
    }

    private static void emit(Particle particle, Location at, int count, Cue.Particle c, Object data, List<Player> receivers, Context ctx) {
        if (receivers.isEmpty()) return;
        int allowed = ctx.allowance().particles(count, receivers.size());
        if (allowed <= 0) return;
        ParticleBuilder b = new ParticleBuilder(particle)
                .location(at)
                .count(allowed)
                .offset(c.spreadX(), c.spreadY(), c.spreadZ())
                .extra(c.speed())
                .receivers(receivers);
        if (data != null) b.data(data);
        b.spawn();
    }

    private static Object data(Cue.Particle c, ItemStack offering) {
        return switch (c.data()) {
            case NONE, UNSUPPORTED -> null;
            case DUST -> new Particle.DustOptions(Color.fromRGB(c.color()), c.size());
            case DUST_TRANSITION -> new Particle.DustTransition(Color.fromRGB(c.color()), Color.fromRGB(c.toColor()), c.size());
            case COLOR -> Color.fromARGB(255, (c.color() >> 16) & 0xFF, (c.color() >> 8) & 0xFF, c.color() & 0xFF);
            case ITEM -> offering.asOne();
            case FLOAT -> c.value();
            case INTEGER -> (int) c.value();
        };
    }

    private static List<Location> points(Cue.Particle c, Context ctx) {
        Location anchor = anchor(c.anchor(), ctx);
        if (anchor == null) return List.of();
        List<Location> out = new ArrayList<>();
        switch (c.shape()) {
            case POINT -> out.add(anchor);
            case RING -> {
                for (int i = 0; i < c.points(); i++) {
                    double a = 2 * Math.PI * i / c.points();
                    out.add(anchor.clone().add(Math.cos(a) * c.radius(), 0, Math.sin(a) * c.radius()));
                }
            }
            case COLUMN -> {
                for (int i = 0; i < c.points(); i++) {
                    double y = c.height() * i / Math.max(1, c.points() - 1);
                    double a = ctx.rng().nextDouble() * 2 * Math.PI;
                    double r = c.radius() * ctx.rng().nextDouble();
                    out.add(anchor.clone().add(Math.cos(a) * r, y, Math.sin(a) * r));
                }
            }
            case SPIRAL -> {
                for (int i = 0; i < c.points(); i++) {
                    double t = (double) i / Math.max(1, c.points() - 1);
                    double a = t * 4 * Math.PI;
                    out.add(anchor.clone().add(Math.cos(a) * c.radius(), t * c.height(), Math.sin(a) * c.radius()));
                }
            }
        }
        return out;
    }

    private static Location anchor(Cue.Anchor anchor, Context ctx) {
        Location block = ctx.machineBlock();
        return switch (anchor) {
            case MACHINE -> block.clone().add(0.5, 0.5, 0.5);
            case TOP -> block.clone().add(0.5, 1.05, 0.5);
            case ABOVE -> block.clone().add(0.5, 1.6, 0.5);
            case OWNER -> {
                Player owner = org.bukkit.Bukkit.getPlayer(ctx.audience().ownerId());
                if (owner != null && owner.isOnline() && owner.getWorld().equals(block.getWorld())
                        && owner.getLocation().distanceSquared(block) < 64 * 64) {
                    yield owner.getEyeLocation().add(0, 0.45, 0);
                }
                yield block.clone().add(0.5, 1.6, 0.5);
            }
        };
    }

    private static void lightning(Context ctx) {
        World world = ctx.machineBlock().getWorld();
        if (world == null) return;
        if (!ctx.allowance().sound(Math.max(1, ctx.audience().members().size()))) return;
        world.strikeLightningEffect(ctx.machineBlock().clone().add(0.5, 1, 0.5));
    }
}
