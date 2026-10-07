package com.voidmachine.it;

import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.LightningStrike;
import org.bukkit.inventory.ItemStack;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ItemMock;
import org.mockbukkit.mockbukkit.entity.LightningStrikeMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * A mock world that implements the two effects MockBukkit leaves out and the plugin uses:
 * visual-only lightning (counted) and item drops with owner locking.
 */
public final class TestWorld extends WorldMock {

    private final ServerMock server;
    public int lightningEffects;
    /** When true, spawning a dropped item fails like a broken server would. */
    public boolean failDrops;

    public TestWorld(ServerMock server, String name) {
        this.server = server;
        setName(name);
    }

    @Override
    public LightningStrike strikeLightningEffect(Location loc) {
        lightningEffects++;
        return new LightningStrikeMock(server, UUID.randomUUID());
    }

    @Override
    public Item dropItem(Location loc, ItemStack item, Consumer<? super Item> function) {
        if (failDrops) throw new IllegalStateException("drop failed (test)");
        LockableItem entity = new LockableItem(server, item);
        entity.setLocation(loc);
        if (function != null) function.accept(entity);
        server.registerEntity(entity);
        return entity;
    }

    /** An item entity whose ownership settings can be set and read back. */
    public static final class LockableItem extends ItemMock {
        private UUID owner;
        private UUID thrower;
        private boolean unlimited;
        private boolean mobPickup = true;
        private int pickupDelay;

        LockableItem(ServerMock server, ItemStack item) {
            super(server, UUID.randomUUID(), item);
        }

        @Override
        public void setOwner(UUID owner) {
            this.owner = owner;
        }

        @Override
        public UUID getOwner() {
            return owner;
        }

        @Override
        public void setThrower(UUID thrower) {
            this.thrower = thrower;
        }

        @Override
        public UUID getThrower() {
            return thrower;
        }

        @Override
        public void setUnlimitedLifetime(boolean unlimited) {
            this.unlimited = unlimited;
        }

        @Override
        public boolean isUnlimitedLifetime() {
            return unlimited;
        }

        @Override
        public void setCanMobPickup(boolean canMobPickup) {
            this.mobPickup = canMobPickup;
        }

        @Override
        public boolean canMobPickup() {
            return mobPickup;
        }

        @Override
        public void setPickupDelay(int delay) {
            this.pickupDelay = delay;
        }

        @Override
        public int getPickupDelay() {
            return pickupDelay;
        }
    }
}
