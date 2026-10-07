package com.voidmachine.paper.presentation;

import com.voidmachine.core.spectator.SpectatorTier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Who is watching a ritual, by tier. Rebuilt every {@code refresh-ticks} from one bounded
 * nearby-player query around the machine — never per tick, never server-wide.
 */
public final class Audience {

    public record Member(Player player, SpectatorTier tier) {
    }

    private final UUID ownerId;
    private List<Member> members = List.of();
    private int onlookers;

    public Audience(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public void refresh(Location center, SpectatorTier.Radii radii, int maxSpectators) {
        World world = center.getWorld();
        List<Member> out = new ArrayList<>();
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null && owner.isOnline()) out.add(new Member(owner, SpectatorTier.OWNER));
        int count = 0;
        if (world != null && maxSpectators > 0) {
            Collection<Player> nearby = world.getNearbyPlayers(center, radii.far());
            List<Player> sorted = new ArrayList<>(nearby);
            sorted.sort(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(center)));
            for (Player p : sorted) {
                if (p.getUniqueId().equals(ownerId)) continue;
                if (out.size() - 1 >= maxSpectators) break;
                SpectatorTier tier = radii.classify(p.getLocation().distanceSquared(center));
                if (tier == SpectatorTier.NONE) continue;
                out.add(new Member(p, tier));
                boolean counts = (tier == SpectatorTier.INNER || tier == SpectatorTier.NEAR)
                        && p.getGameMode() != GameMode.SPECTATOR && !p.hasMetadata("vanished");
                if (counts) count++;
            }
        }
        this.members = List.copyOf(out);
        this.onlookers = count;
    }

    /** Members still online; stale entries (quit since the last refresh) are skipped. */
    public List<Member> members() {
        List<Member> live = new ArrayList<>(members.size());
        for (Member m : members) if (m.player().isOnline()) live.add(m);
        return live;
    }

    public List<Player> players(SpectatorTier.Audience to) {
        List<Player> out = new ArrayList<>();
        for (Member m : members) if (m.tier().hears(to) && m.player().isOnline()) out.add(m.player());
        return out;
    }

    /** Onlookers in the inner and near tiers, not counting the owner, spectator-mode or vanished players. */
    public int onlookers() {
        return onlookers;
    }

    public UUID ownerId() {
        return ownerId;
    }
}
