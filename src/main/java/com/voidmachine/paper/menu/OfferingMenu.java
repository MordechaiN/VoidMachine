package com.voidmachine.paper.menu;

import com.voidmachine.core.config.Settings;
import com.voidmachine.core.outcome.OutcomeTable;
import com.voidmachine.paper.i18n.Messages;
import com.voidmachine.paper.item.OfferingRules;
import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.ritual.RitualService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The offering screen. Bedrock-, touch- and controller-friendly by construction:
 * <ul>
 *   <li>the offering is <b>selected</b>, never moved: the player taps a stack in their own inventory;
 *       the item stays there until the ritual commits, so closing, dying, quitting or crashing while
 *       this screen is open can never lose anything;</li>
 *   <li>every click on every slot is cancelled; only taps are interpreted (no drag, shift or hotkeys needed);</li>
 *   <li>big single-purpose buttons, text in item names (always visible, unlike lore on touch screens);</li>
 *   <li>the title never changes (Bedrock reopens inventories when it does).</li>
 * </ul>
 *
 * <pre>
 *  .  .  .  .  [?]  .  .  .  .        ? = what is this
 *  . [1][¼] .  [O] [½][A] .  .        O = selected offering, 1/¼/½/A = how many
 *  [x] .  .  . [GO]  .  .  . [i]      GO = offer, x = leave, i = odds
 * </pre>
 */
public final class OfferingMenu implements InventoryHolder {

    static final int HEADER = 4;
    static final int OFFERING = 13;
    static final int START = 22;
    static final int LEAVE = 18;
    static final int INFO = 26;
    static final int[] PRESET_SLOTS = {10, 11, 15, 16};

    private final Plugin plugin;
    private final Messages messages;
    private final RitualService rituals;
    private final Supplier<Settings> settings;
    private final Machine machine;
    private final Player player;
    private final Messages.Variant variant;
    private final Inventory inventory;

    private int selectedSlot = -1;
    private ItemStack selected;
    private int amount;
    private boolean submitted;

    public OfferingMenu(Plugin plugin, Messages messages, RitualService rituals, Supplier<Settings> settings,
                        Machine machine, Player player) {
        this.plugin = plugin;
        this.messages = messages;
        this.rituals = rituals;
        this.settings = settings;
        this.machine = machine;
        this.player = player;
        this.variant = messages.variant(player);
        this.inventory = Bukkit.createInventory(this, 27, messages.render(variant, "offering.title"));
    }

    public void open() {
        int held = player.getInventory().getHeldItemSlot();
        ItemStack inHand = player.getInventory().getItem(held);
        if (inHand != null && !inHand.isEmpty() && OfferingRules.check(inHand, settings.get().offering()).isEmpty()) {
            select(held, inHand);
        }
        render();
        player.openInventory(inventory);
    }

    public Machine machine() {
        return machine;
    }

    // ------------------------------------------------------------------------------------------
    // Clicks (all already cancelled by the listener)
    // ------------------------------------------------------------------------------------------

    void clickTop(int slot) {
        if (submitted) return;
        if (slot == START) {
            start();
            return;
        }
        if (slot == LEAVE) {
            Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
            return;
        }
        if (selected != null) {
            int[] presets = presets();
            for (int i = 0; i < PRESET_SLOTS.length; i++) {
                if (slot == PRESET_SLOTS[i] && i < presets.length) {
                    amount = presets[i];
                    click(1.4f);
                    render();
                    return;
                }
            }
        }
    }

    /** A tap on the player's own inventory selects that stack. */
    void clickBottom(int playerSlot) {
        if (submitted || playerSlot < 0 || playerSlot >= 36) return;
        ItemStack item = player.getInventory().getItem(playerSlot);
        if (item == null || item.isEmpty()) return;
        Optional<OfferingRules.Refusal> refusal = OfferingRules.check(item, settings.get().offering());
        if (refusal.isPresent()) {
            messages.actionBar(player, refusal.get().messageKey());
            player.playSound(player.getLocation(), "block.note_block.bass", 0.6f, 0.6f);
            return;
        }
        select(playerSlot, item);
        click(1.0f);
        render();
    }

    private void select(int slot, ItemStack item) {
        selectedSlot = slot;
        selected = item.asOne();
        amount = available();
    }

    private void start() {
        if (selected == null) {
            messages.actionBar(player, "offering.choose-first");
            return;
        }
        ItemStack now = player.getInventory().getItem(selectedSlot);
        if (now == null || !now.isSimilar(selected)) {
            selected = null;
            selectedSlot = -1;
            messages.actionBar(player, "ritual.offering-moved");
            render();
            return;
        }
        amount = Math.min(amount, available());
        RitualService.BeginResult result = rituals.begin(player, machine, selectedSlot, selected, amount);
        if (result == RitualService.BeginResult.ACCEPTED) {
            submitted = true;
            messages.actionBar(player, "ritual.listening");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.getOpenInventory().getTopInventory().getHolder(false) == this) player.closeInventory();
            });
            return;
        }
        messages.send(player, "offering.result." + key(result), seconds(result));
        player.playSound(player.getLocation(), "block.note_block.bass", 0.6f, 0.6f);
        render();
    }

    // ------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------

    void render() {
        ItemStack pane = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < 27; i++) inventory.setItem(i, pane);
        inventory.setItem(HEADER, button(Material.ENDER_EYE, "offering.header.name", "offering.header.lore"));
        inventory.setItem(LEAVE, button(Material.DARK_OAK_DOOR, "offering.leave.name", null));
        inventory.setItem(INFO, oddsBook());
        if (selected == null) {
            inventory.setItem(OFFERING, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "offering.empty.name", "offering.empty.lore"));
            inventory.setItem(START, button(Material.GRAY_DYE, "offering.button.choose", null));
            return;
        }
        TagResolver[] r = resolvers();
        ItemStack preview = selected.clone();
        preview.setAmount(Math.max(1, Math.min(amount, preview.getMaxStackSize())));
        ItemMeta pm = preview.getItemMeta();
        List<Component> lore = new ArrayList<>(messages.itemLore(variant, "offering.preview.lore", r));
        pm.lore(lore);
        preview.setItemMeta(pm);
        inventory.setItem(OFFERING, preview);

        int[] presets = presets();
        int max = presets[presets.length - 1];
        for (int i = 0; i < PRESET_SLOTS.length && i < presets.length; i++) {
            int value = presets[i];
            ItemStack b = new ItemStack(Material.AMETHYST_SHARD, Math.max(1, Math.min(64, value)));
            ItemMeta m = b.getItemMeta();
            String key = value == max ? "offering.amount.all" : value == 1 ? "offering.amount.one" : "offering.amount.some";
            m.displayName(messages.itemText(variant, key, Placeholder.unparsed("amount", Integer.toString(value))));
            if (value == amount) m.setEnchantmentGlintOverride(true);
            b.setItemMeta(m);
            inventory.setItem(PRESET_SLOTS[i], b);
        }

        Optional<RitualService.BeginResult> blocked = rituals.precheck(player, machine);
        if (blocked.isPresent()) {
            ItemStack b = button(Material.BARRIER, "offering.button." + key(blocked.get()), null, seconds(blocked.get()));
            inventory.setItem(START, b);
        } else {
            ItemStack go = button(Material.NETHER_STAR, "offering.button.offer", "offering.button.offer-lore", r);
            ItemMeta m = go.getItemMeta();
            m.setEnchantmentGlintOverride(true);
            go.setItemMeta(m);
            inventory.setItem(START, go);
        }
    }

    private ItemStack oddsBook() {
        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.displayName(messages.itemText(variant, "offering.info.name"));
        List<Component> lore = new ArrayList<>(messages.itemLore(variant, "offering.info.lore"));
        Settings s = settings.get();
        Settings.Profile profile = s.profiles().get(machine.record().profile());
        if (profile != null) {
            for (OutcomeTable.Entry e : profile.table().entries()) {
                if (e.units() == 0) continue;
                double p = (double) e.units() / profile.table().entries().stream().mapToLong(OutcomeTable.Entry::units).sum();
                String rarity = p >= 0.30 ? "common" : p >= 0.10 ? "uncommon" : p >= 0.02 ? "rare" : "legendary";
                String nameKey = "outcomes." + e.definition().id() + ".name";
                Component name = messages.has(nameKey) ? messages.render(variant, nameKey) : Component.text(e.definition().id());
                lore.add(messages.render(variant, "offering.info.line", Placeholder.component("outcome", name),
                                Placeholder.component("rarity", messages.render(variant, "rarity." + rarity)))
                        .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
        }
        meta.lore(lore);
        book.setItemMeta(meta);
        return book;
    }

    private ItemStack button(Material material, String nameKey, String loreKey, TagResolver... r) {
        ItemStack is = new ItemStack(material);
        ItemMeta meta = is.getItemMeta();
        meta.displayName(messages.itemText(variant, nameKey, r));
        if (loreKey != null) meta.lore(messages.itemLore(variant, loreKey, r));
        is.setItemMeta(meta);
        return is;
    }

    private static ItemStack pane(Material material) {
        ItemStack is = new ItemStack(material);
        ItemMeta meta = is.getItemMeta();
        meta.setHideTooltip(true);
        is.setItemMeta(meta);
        return is;
    }

    private TagResolver[] resolvers() {
        return new TagResolver[]{
                Placeholder.unparsed("amount", Integer.toString(amount)),
                Placeholder.component("item", selected.effectiveName()),
                Placeholder.unparsed("machine", machine.record().displayName())
        };
    }

    private TagResolver seconds(RitualService.BeginResult result) {
        long ms = switch (result) {
            case MACHINE_RESTING -> machine.restingMillis();
            case PLAYER_COOLDOWN -> rituals.playerCooldownMillis(player.getUniqueId());
            default -> 0;
        };
        return Placeholder.unparsed("seconds", Long.toString((ms + 999) / 1000));
    }

    private int available() {
        if (selectedSlot < 0) return 0;
        ItemStack now = player.getInventory().getItem(selectedSlot);
        if (now == null || !now.isSimilar(selected)) return 0;
        return Math.min(now.getAmount(), settings.get().offering().maxAmount());
    }

    private int[] presets() {
        int max = Math.max(1, available());
        TreeSet<Integer> set = new TreeSet<>(List.of(1, Math.max(1, max / 4), Math.max(1, max / 2), max));
        return set.stream().mapToInt(Integer::intValue).toArray();
    }

    private void click(float pitch) {
        player.playSound(player.getLocation(), "ui.button.click", 0.5f, pitch);
    }

    static String key(RitualService.BeginResult r) {
        return r.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
