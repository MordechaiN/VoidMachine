package com.voidmachine.paper.presentation;

import com.voidmachine.core.script.Cue;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The floating offering above a machine. Display entities are presentation only:
 * <ul>
 *   <li>non-persistent — never saved with the chunk, so a crash cannot leave one behind;</li>
 *   <li>tagged with the ritual id — an orphan sweep removes any tagged display it does not track;</li>
 *   <li>capped per ritual and removed on finish, abort and shutdown.</li>
 * </ul>
 * Java clients see them; Bedrock clients (Geyser) get the same moment through particles and the
 * machine's charge glow.
 */
public final class DisplayService {

    private final NamespacedKey key;
    private final Map<UUID, Handle> handles = new HashMap<>();

    public DisplayService(NamespacedKey key) {
        this.key = key;
    }

    public Handle open(UUID ritualId, Location machineBlock, ItemStack offering, int maxEntities) {
        Handle h = new Handle(ritualId, machineBlock.clone().add(0.5, 0, 0.5), offering.asOne(), maxEntities);
        handles.put(ritualId, h);
        return h;
    }

    public int tracked() {
        int n = 0;
        for (Handle h : handles.values()) if (h.entity != null && h.entity.isValid()) n++;
        return n;
    }

    public void removeAll() {
        for (Handle h : Map.copyOf(handles).values()) h.close();
        handles.clear();
    }

    /** Removes displays tagged by VoidMachine that no live ritual owns (e.g. left by a hard crash of an old version). */
    public int sweep(Chunk chunk) {
        int removed = 0;
        for (Entity e : chunk.getEntities()) {
            if (!(e instanceof Display)) continue;
            String owner = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (owner == null) continue;
            Handle h = null;
            try {
                h = handles.get(UUID.fromString(owner));
            } catch (IllegalArgumentException ignored) {
                // malformed tag: certainly an orphan
            }
            if (h == null || h.entity != e) {
                e.remove();
                removed++;
            }
        }
        return removed;
    }

    public final class Handle {
        private final UUID ritualId;
        private final Location base;
        private final ItemStack offering;
        private final int max;
        private ItemDisplay entity;
        private float angle;
        private boolean big;

        private Handle(UUID ritualId, Location base, ItemStack offering, int max) {
            this.ritualId = ritualId;
            this.base = base;
            this.offering = offering;
            this.max = max;
        }

        public void apply(Cue.DisplayAction action, ItemStack reward) {
            if (max <= 0) return;
            switch (action) {
                case RISE -> {
                    ItemDisplay d = ensure(base.clone().add(0, 1.0, 0), offering, 0.05f);
                    if (d == null) return;
                    move(d, base.clone().add(0, 1.6, 0), 10);
                    transform(d, 0.6f, angle, 10);
                }
                case SPIN -> {
                    if (entity == null) return;
                    angle += (float) Math.PI;
                    transform(entity, big ? 0.85f : 0.6f, angle, 20);
                }
                case PULSE -> {
                    if (entity == null) return;
                    big = !big;
                    transform(entity, big ? 0.85f : 0.6f, angle, 4);
                }
                case IMPLODE -> {
                    if (entity == null) return;
                    move(entity, base.clone().add(0, 1.05, 0), 8);
                    transform(entity, 0.02f, angle + (float) Math.PI, 8);
                }
                case ASCEND -> {
                    ItemDisplay d = ensure(base.clone().add(0, 1.6, 0), reward != null ? reward : offering, 0.6f);
                    if (d == null) return;
                    d.setGlowing(true);
                    move(d, base.clone().add(0, 5.0, 0), 40);
                    transform(d, 1.2f, angle + 2 * (float) Math.PI, 40);
                }
                case HIDE -> close();
                case REWARD -> {
                    ItemDisplay d = ensure(base.clone().add(0, 1.6, 0), reward != null ? reward : offering, 0.05f);
                    if (d == null) return;
                    d.setItemStack((reward != null ? reward : offering).asOne());
                    d.setGlowing(true);
                    move(d, base.clone().add(0, 1.8, 0), 10);
                    transform(d, 0.95f, angle + (float) Math.PI, 10);
                }
            }
        }

        private ItemDisplay ensure(Location at, ItemStack item, float startScale) {
            if (entity != null && entity.isValid()) return entity;
            World world = at.getWorld();
            if (world == null || !world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return null;
            entity = world.spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.getPersistentDataContainer().set(key, PersistentDataType.STRING, ritualId.toString());
                d.setItemStack(item.asOne());
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(0.75f);
                d.setShadowRadius(0f);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(startScale), new AxisAngle4f()));
            });
            return entity;
        }

        private void move(ItemDisplay d, Location to, int ticks) {
            d.setTeleportDuration(Math.min(59, Math.max(0, ticks)));
            d.teleport(to);
        }

        private void transform(ItemDisplay d, float scale, float yaw, int ticks) {
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(yaw, 0, 1, 0),
                    new Vector3f(scale), new AxisAngle4f()));
        }

        public void close() {
            if (entity != null && entity.isValid()) entity.remove();
            entity = null;
            handles.remove(ritualId);
        }
    }
}
