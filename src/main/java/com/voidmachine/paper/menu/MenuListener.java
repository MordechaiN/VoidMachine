package com.voidmachine.paper.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Cancels every click and drag in VoidMachine screens (identified by their holder, not by title) and
 * routes taps to the menu. Runs at LOWEST so the event is cancelled before any other plugin sees it.
 */
public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (!(holder instanceof OfferingMenu) && !(holder instanceof ChamberMenu)) return;
        event.setCancelled(true);
        if (!(holder instanceof OfferingMenu menu)) return;
        if (!(event.getWhoClicked() instanceof Player)) return;
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) return;
        if (clicked == event.getView().getTopInventory()) {
            menu.clickTop(event.getRawSlot());
        } else if (clicked == event.getView().getBottomInventory()) {
            menu.clickBottom(event.getSlot());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (holder instanceof OfferingMenu || holder instanceof ChamberMenu) event.setCancelled(true);
    }
}
