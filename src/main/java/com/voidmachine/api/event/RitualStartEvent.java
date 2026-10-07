package com.voidmachine.api.event;

import com.voidmachine.api.MachineView;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * A player pressed the offering button. Fired before anything is recorded or taken; cancel it to
 * refuse the offering (for example from a quest, region or event plugin). Nothing has been rolled yet.
 */
public final class RitualStartEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final MachineView machine;
    private final ItemStack offering;
    private final int amount;
    private boolean cancelled;

    public RitualStartEvent(Player player, MachineView machine, ItemStack offering, int amount) {
        this.player = player;
        this.machine = machine;
        this.offering = offering.clone();
        this.amount = amount;
    }

    public Player player() {
        return player;
    }

    public MachineView machine() {
        return machine;
    }

    /** A copy of the offered item (amount 1). */
    public ItemStack offering() {
        return offering.clone();
    }

    public int amount() {
        return amount;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
