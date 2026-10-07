package com.voidmachine.it;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.InventoryHolder;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.inventory.ChestInventoryMock;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;

/**
 * MockBukkit leaves {@code Inventory#getHolder(boolean)} unimplemented. The plugin uses it (as it
 * should on Paper: it never snapshots block states), so plugin-created menus get an inventory that
 * answers it the same way a real custom inventory does.
 *
 * <p>The suppression is for a raw return type inherited from MockBukkit's {@code ServerMock#getBanList}
 * (every subclass of it triggers the warning); this class adds no unchecked code of its own.</p>
 */
@SuppressWarnings("unchecked")
public final class VmServerMock extends ServerMock {

    @Override
    public InventoryMock createInventory(InventoryHolder owner, int size, Component title) {
        if (size < 9 || size > 54 || size % 9 != 0) throw new IllegalArgumentException("bad size " + size);
        return new HolderChest(owner, size);
    }

    private static final class HolderChest extends ChestInventoryMock {
        HolderChest(InventoryHolder owner, int size) {
            super(owner, size);
        }

        @Override
        public InventoryHolder getHolder(boolean useSnapshot) {
            return getHolder();
        }
    }
}
