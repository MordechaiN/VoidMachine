package com.voidmachine.paper.item;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Converts offerings to and from the journal's template bytes. Templates are always a single item
 * ({@code amount = 1}) serialized with {@link ItemStack#serializeAsBytes()}: full components and the
 * data version are kept, so enchantments, names, lore, trims, potion contents, custom data and
 * persistent data survive restarts and Minecraft upgrades, and oversized rewards never hit the
 * codec's stack-size limit.
 */
public final class ItemCodec {

    private ItemCodec() {
    }

    public static byte[] encodeTemplate(ItemStack item) {
        if (item == null || item.isEmpty()) throw new IllegalArgumentException("cannot encode an empty item");
        return item.asOne().serializeAsBytes();
    }

    /**
     * Restores a single-item template written by {@link #encodeTemplate}.
     *
     * @throws IllegalArgumentException if the bytes are not a valid item for this server
     */
    public static ItemStack decodeTemplate(byte[] bytes) {
        ItemStack item = ItemStack.deserializeBytes(bytes);
        if (item.isEmpty()) throw new IllegalArgumentException("template decodes to an empty item");
        return item.asOne();
    }

    /**
     * The item's name for messages: its custom name, else its item name, else the client-translated
     * default name (potion, banner and similar variants are resolved by the server's translation key).
     * Unlike {@code effectiveName()} it carries no rarity colour, so messages style it consistently.
     */
    public static Component name(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasCustomName()) {
            Component custom = meta.customName();
            if (custom != null) return custom;
        }
        if (meta != null && meta.hasItemName()) return meta.itemName();
        return Component.translatable(item.translationKey());
    }

    /** {@code minecraft:diamond} style key for logs and admin output (never the full NBT). */
    public static String key(ItemStack item) {
        return item.getType().getKey().asString();
    }
}
