package com.voidmachine.paper.listener;

import com.voidmachine.core.config.Settings;
import com.voidmachine.core.custody.CustodyEngine;
import com.voidmachine.paper.i18n.BedrockDetector;
import com.voidmachine.paper.presentation.RitualDirector;
import com.voidmachine.paper.ritual.CustodyService;
import com.voidmachine.paper.ritual.RitualService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Player lifecycle. Nothing here ever refunds or re-rolls: quitting, dying or teleporting only
 * changes <em>when</em> a sealed verdict is delivered.
 */
public final class PlayerListener implements Listener {

    private final Plugin plugin;
    private final CustodyService custody;
    private final RitualService rituals;
    private final RitualDirector director;
    private final BedrockDetector bedrock;
    private final Supplier<Settings> settings;
    private final Consumer<java.util.UUID> onQuit;

    public PlayerListener(Plugin plugin, CustodyService custody, RitualService rituals, RitualDirector director,
                          BedrockDetector bedrock, Supplier<Settings> settings, Consumer<java.util.UUID> onQuit) {
        this.plugin = plugin;
        this.custody = custody;
        this.rituals = rituals;
        this.director = director;
        this.bedrock = bedrock;
        this.settings = settings;
        this.onQuit = onQuit;
    }

    /**
     * The player's ledger was just loaded from their data file: this is the moment it proves what was
     * saved. Reconcile now, before the player can interact with anything.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        custody.reconcile(event.getPlayer(), CustodyEngine.Mode.FRESH_LOAD);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        java.util.UUID id = event.getPlayer().getUniqueId();
        bedrock.forget(id);
        custody.forgetPlayer(id);
        rituals.forgetPlayer(id);
        onQuit.accept(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !p.isDead()) custody.payOwed(p, false);
        }, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        custody.nudge(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        custody.nudge(event.getPlayer().getUniqueId());
    }

    /** Keeps the offering player near the machine; the camera stays free. Falling is never blocked. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (rituals.isIdle()) return;
        Location origin = director.tetherOf(event.getPlayer().getUniqueId());
        if (origin == null) return;
        Location to = event.getTo();
        if (!to.getWorld().equals(origin.getWorld())) {
            director.releaseTether(event.getPlayer().getUniqueId());
            return;
        }
        Settings s = settings.get();
        double radius = s == null ? 1.5 : s.presentation().tetherRadius();
        double dx = to.getX() - origin.getX();
        double dz = to.getZ() - origin.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq <= radius * radius) return;
        if (distSq > 64) { // knocked or moved far by something else: let go rather than fight it
            director.releaseTether(event.getPlayer().getUniqueId());
            return;
        }
        Location back = event.getFrom().clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        if (to.getY() < back.getY()) back.setY(to.getY());
        event.setTo(back);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location origin = director.tetherOf(event.getPlayer().getUniqueId());
        if (origin == null) return;
        if (!event.getTo().getWorld().equals(origin.getWorld()) || event.getTo().distanceSquared(origin) > 64) {
            director.releaseTether(event.getPlayer().getUniqueId());
        }
    }
}
