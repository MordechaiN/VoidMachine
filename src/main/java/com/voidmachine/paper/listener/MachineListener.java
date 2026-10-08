package com.voidmachine.paper.listener;

import com.voidmachine.core.config.Settings;
import com.voidmachine.paper.i18n.Messages;
import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.machine.MachineRegistry;
import com.voidmachine.paper.menu.OfferingMenu;
import com.voidmachine.paper.ritual.ActiveRitual;
import com.voidmachine.paper.ritual.CustodyService;
import com.voidmachine.paper.ritual.RitualService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Opens the offering screen and makes registered machine blocks indestructible through gameplay.
 * Machines are removed only with {@code /vm admin remove}.
 */
public final class MachineListener implements Listener {

    private final Plugin plugin;
    private final MachineRegistry machines;
    private final RitualService rituals;
    private final CustodyService custody;
    private final Messages messages;
    private final Supplier<Settings> settings;
    private final Map<UUID, Long> lastInteract = new HashMap<>();

    public MachineListener(Plugin plugin, MachineRegistry machines, RitualService rituals, CustodyService custody,
                           Messages messages, Supplier<Settings> settings) {
        this.plugin = plugin;
        this.machines = machines;
        this.rituals = rituals;
        this.custody = custody;
        this.messages = messages;
        this.settings = settings;
    }

    /**
     * HIGHEST, and deliberately not ignoring cancelled events: the machine exists only where an admin
     * registered it, and vanilla behaviour of the block (charging, exploding anchors) must always be
     * suppressed — for both hands. Access is governed by permissions.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Machine machine = machines.at(block);
        if (machine == null) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player p = event.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        Settings s = settings.get();
        long now = System.currentTimeMillis();
        long gap = s == null ? 400 : s.machines().interactCooldownMillis();
        Long last = lastInteract.get(p.getUniqueId());
        if (last != null && now - last < gap) return;
        lastInteract.put(p.getUniqueId(), now);

        // Held rewards come first: the machine gives back what it owes before it takes again.
        if (s != null) custody.payOwed(p, false);

        ActiveRitual own = rituals.byPlayer(p.getUniqueId());
        if (own != null) {
            messages.actionBar(p, "offering.result.already-in-ritual");
            return;
        }
        if (machine.isBusy()) {
            ActiveRitual other = rituals.byId(machine.activeRitual());
            messages.actionBar(p, "offering.result.machine-busy",
                    Placeholder.unparsed("player", other == null ? "?" : other.playerName()));
            return;
        }
        Optional<RitualService.BeginResult> refused = rituals.precheck(p, machine);
        if (refused.isPresent() && blocksOpening(refused.get())) {
            messages.send(p, "offering.result." + refused.get().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                    Placeholder.unparsed("seconds", Long.toString(seconds(refused.get(), p, machine))));
            return;
        }
        new OfferingMenu(plugin, messages, rituals, settings, machine, p).open();
        p.playSound(machine.center(), "block.respawn_anchor.ambient", 0.6f, 0.7f);
    }

    private static boolean blocksOpening(RitualService.BeginResult r) {
        return switch (r) {
            case MACHINE_RESTING, PLAYER_COOLDOWN, TOO_MANY_UNCLAIMED, TOO_MANY_RITUALS -> false; // the screen explains it
            default -> true;
        };
    }

    private long seconds(RitualService.BeginResult r, Player p, Machine m) {
        long ms = switch (r) {
            case MACHINE_RESTING -> m.restingMillis();
            case PLAYER_COOLDOWN -> rituals.playerCooldownMillis(p.getUniqueId());
            default -> 0;
        };
        return (ms + 999) / 1000;
    }

    public void forget(UUID player) {
        lastInteract.remove(player);
    }

    // ------------------------------------------------------------------------------------------
    // Protection
    // ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Machine m = machines.at(event.getBlock());
        if (m == null) return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        if (p.hasPermission("voidmachine.admin")) {
            messages.send(p, "machine.protected-admin", Placeholder.unparsed("machine", m.id()));
        } else {
            messages.actionBar(p, "machine.protected");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        strip(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        strip(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block b : event.getBlocks()) {
            if (machines.at(b) != null || machines.at(b.getRelative(event.getDirection())) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) {
            if (machines.at(b) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (machines.at(event.getToBlock()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (machines.at(event.getBlock()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (machines.at(event.getBlock()) != null) event.setCancelled(true);
    }

    /** Dispensers can charge respawn anchors with glowstone; not ours. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (event.getBlock().getBlockData() instanceof Directional d
                && machines.at(event.getBlock().getRelative(d.getFacing())) != null) {
            event.setCancelled(true);
        }
    }

    private void strip(List<Block> blocks) {
        if (blocks.isEmpty() || machines.size() == 0) return;
        blocks.removeIf(b -> machines.at(b) != null);
    }
}
