package com.voidmachine.paper.item;

import org.bukkit.inventory.ItemStack;

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
     * @throws IllegalArgumentException if the bytes are not a valid item for this server
     */
    public static ItemStack decodeTemplate(byte[] bytes) {
        ItemStack item = ItemStack.deserializeBytes(bytes);
        if (item.isEmpty()) throw new IllegalArgumentException("template decodes to an empty item");
        return item.asOne();
    }

    /** {@code minecraft:diamond} style key for logs and admin output (never the full NBT). */
    public static String key(ItemStack item) {
        return item.getType().getKey().asString();
    }
}
