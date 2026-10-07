package com.voidmachine.paper.menu;

import com.voidmachine.core.outcome.OutcomeTier;
import com.voidmachine.paper.i18n.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Optional "ritual chamber" screen shown during a ritual (presentation.ritual-chamber-gui). A static
 * 3-row layout whose centre item reflects the phase; the title never changes (Bedrock reopens
 * inventories on title changes). Every click is cancelled by {@link MenuListener}; closing it is
 * harmless — the ritual continues in the world.
 */
public final class ChamberMenu implements InventoryHolder {

    private static final int CENTER = 13;

    private final Messages messages;
    private final Messages.Variant variant;
    private final Inventory inventory;

    public ChamberMenu(Messages messages, Player owner) {
        this.messages = messages;
        this.variant = messages.variant(owner);
        this.inventory = Bukkit.createInventory(this, 27, messages.render(variant, "chamber.title"));
        fill(Material.BLACK_STAINED_GLASS_PANE);
        status(Material.ENDER_EYE, "chamber.status.stir");
    }

    public void open(Player owner) {
        owner.openInventory(inventory);
    }

    public void status(Material icon, String key, TagResolver... resolvers) {
        ItemStack is = new ItemStack(icon);
        ItemMeta meta = is.getItemMeta();
        meta.displayName(messages.itemText(variant, key, resolvers));
        is.setItemMeta(meta);
        inventory.setItem(CENTER, is);
    }

    public void reveal(OutcomeTier tier, String key, TagResolver... resolvers) {
        Material border = switch (tier) {
            case LOSS -> Material.RED_STAINED_GLASS_PANE;
            case PARTIAL -> Material.ORANGE_STAINED_GLASS_PANE;
            case NEUTRAL -> Material.LIGHT_GRAY_STAINED_GLASS_PANE;
            case WIN -> Material.LIME_STAINED_GLASS_PANE;
            case GREAT -> Material.YELLOW_STAINED_GLASS_PANE;
            case JACKPOT -> Material.MAGENTA_STAINED_GLASS_PANE;
        };
        Material icon = switch (tier) {
            case LOSS -> Material.BARRIER;
            case PARTIAL -> Material.FLINT;
            case NEUTRAL -> Material.ENDER_PEARL;
            case WIN -> Material.EMERALD;
            case GREAT -> Material.NETHER_STAR;
            case JACKPOT -> Material.END_CRYSTAL;
        };
        fill(border);
        ItemStack is = new ItemStack(icon);
        ItemMeta meta = is.getItemMeta();
        meta.displayName(messages.itemText(variant, key, resolvers));
        if (tier == OutcomeTier.JACKPOT) meta.setEnchantmentGlintOverride(true);
        is.setItemMeta(meta);
        inventory.setItem(CENTER, is);
    }

    public void close(Player owner) {
        if (owner != null && owner.getOpenInventory().getTopInventory().getHolder(false) == this) owner.closeInventory();
    }

    private void fill(Material pane) {
        ItemStack is = new ItemStack(pane);
        ItemMeta meta = is.getItemMeta();
        meta.setHideTooltip(true);
        is.setItemMeta(meta);
        for (int i = 0; i < 27; i++) if (i != CENTER) inventory.setItem(i, is);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
