package com.voidmachine.it;

import com.voidmachine.api.event.RitualCommitEvent;
import com.voidmachine.core.ledger.PlayerLedger;
import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.ritual.ActiveRitual;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Randomized chaos on the real plugin: offers, quits, joins, full inventories, claims, silently failing
 * saves, chunk unloads, admin resolves, crashes and restarts, in seeded random order. Afterwards every
 * player must own exactly what the protocol promises:
 *
 * <pre>initial − Σ offered + Σ reward, over every ritual whose capture reached the player's data file</pre>
 *
 * A capture reaches the data file with the first successful save after it (that save contains the
 * inventory without the offering). A capture that is thrown away by loading older data before such a
 * save did not happen: the offering comes back with the old data, and the record is discarded.
 */
class ChaosTest {

    private static final Material[] MATERIALS = {Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT, Material.IRON_INGOT,
            Material.REDSTONE, Material.LAPIS_LAZULI};
    private static final String[] PROFILES = {"t-double", "t-loss", "t-tithe", "t-jackpot"};

    private Harness h;

    @AfterEach
    void tearDown() {
        if (h != null) h.close();
    }

    private record Committed(UUID player, int input, int reward) {
    }

    private static boolean ledgerHas(String ledger, UUID id) {
        try {
            return PlayerLedger.decode(ledger).contains(id);
        } catch (PlayerLedger.LedgerFormatException e) {
            throw new AssertionError(e);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 7L, 42L, 2026L})
    void itemsAreConservedUnderChaos(long seed) throws Exception {
        SplittableRandom rng = new SplittableRandom(seed);
        h = new Harness(c -> c.replace("max-active-rituals: 4", "max-active-rituals: 3"));
        List<Machine> machines = new ArrayList<>();
        for (int i = 0; i < 4; i++) machines.add(h.machine("m" + i, PROFILES[i], i * 20, 64, 0));

        Map<UUID, Committed> committed = new ConcurrentHashMap<>();
        java.util.Set<UUID> durable = ConcurrentHashMap.newKeySet();
        Map<UUID, java.util.Set<UUID>> unsaved = new ConcurrentHashMap<>();
        Map<UUID, TestPlayer> byId = new HashMap<>();
        Listener spy = new Listener() {
            @EventHandler
            public void on(RitualCommitEvent e) {
                UUID id = e.ritual().ritualId();
                committed.put(id, new Committed(e.ritual().playerId(), e.ritual().inputAmount(), e.ritual().rewardAmount()));
                if (ledgerHas(byId.get(e.ritual().playerId()).savedLedger(), id)) durable.add(id);
                else unsaved.computeIfAbsent(e.ritual().playerId(), k -> ConcurrentHashMap.newKeySet()).add(id);
            }
        };
        h.server.getPluginManager().registerEvents(spy, h.plugin);

        Map<UUID, Material> material = new HashMap<>();
        Map<UUID, Integer> initial = new HashMap<>();
        List<TestPlayer> players = new ArrayList<>();
        for (int i = 0; i < MATERIALS.length; i++) {
            TestPlayer p = h.player("C" + i, machines.get(i % machines.size()));
            UUID pid = p.getUniqueId();
            byId.put(pid, p);
            p.onPersist = ledger -> {
                java.util.Set<UUID> pending = unsaved.remove(pid);
                if (pending != null) durable.addAll(pending);
            };
            p.onLoadSaved = () -> unsaved.remove(pid);
            p.getInventory().setItem(0, new ItemStack(MATERIALS[i], 64));
            p.getInventory().setItem(1, new ItemStack(MATERIALS[i], 64));
            p.persist();
            material.put(p.getUniqueId(), MATERIALS[i]);
            initial.put(p.getUniqueId(), 128);
            players.add(p);
        }

        int crashes = 0;
        for (int step = 0; step < 260; step++) {
            TestPlayer p = players.get(rng.nextInt(players.size()));
            int action = rng.nextInt(100);
            if (action < 30) {
                if (!p.isOnline()) continue;
                Machine m = machines.get(rng.nextInt(machines.size()));
                int slot = -1;
                for (int s = 0; s < 36; s++) {
                    ItemStack it = p.getInventory().getItem(s);
                    if (it != null && it.getType() == material.get(p.getUniqueId())) {
                        slot = s;
                        break;
                    }
                }
                if (slot < 0) continue;
                p.getInventory().setHeldItemSlot(slot < 9 ? slot : 0);
                if (slot >= 9) continue;
                p.teleport(m.location().clone().add(0.5, 1, 2.5));
                h.interact(p, m);
                if (Harness.hasMenuOpen(p)) {
                    if (rng.nextBoolean()) h.clickTop(p, 10 + rng.nextInt(2) * 1); // preset 1 or a quarter
                    h.clickTop(p, 22);
                }
            } else if (action < 50) {
                h.ticks(1 + rng.nextInt(80));
            } else if (action < 56) {
                if (p.isOnline()) h.quit(p);
            } else if (action < 64) {
                if (!p.isOnline()) h.join(p);
            } else if (action < 70) {
                p.saveSilentlyFails = rng.nextInt(4) == 0;
            } else if (action < 76) {
                if (!p.isOnline()) continue;
                for (int s = 2; s < 36; s++) {
                    ItemStack it = p.getInventory().getItem(s);
                    if (it == null || it.isEmpty()) p.getInventory().setItem(s, new ItemStack(Material.DIRT, 64));
                }
            } else if (action < 82) {
                if (!p.isOnline()) continue;
                for (int s = 0; s < 36; s++) {
                    ItemStack it = p.getInventory().getItem(s);
                    if (it != null && it.getType() == Material.DIRT) p.getInventory().setItem(s, null);
                }
                p.performCommand("vm claim");
            } else if (action < 86) {
                h.settle();
            } else if (action < 89) {
                Machine m = machines.get(rng.nextInt(machines.size()));
                h.server.getPluginManager().callEvent(new ChunkUnloadEvent(h.world.getChunkAt(m.record().x() >> 4, m.record().z() >> 4), true));
            } else if (action < 92) {
                List<ActiveRitual> running = List.copyOf(h.rt().rituals().active());
                if (!running.isEmpty()) {
                    h.server.dispatchCommand(h.server.getConsoleSender(),
                            "vm admin resolve " + running.get(rng.nextInt(running.size())).record().shortId());
                }
            } else if (action < 96) {
                h.crashAndRestart();
                h.server.getPluginManager().registerEvents(spy, h.plugin);
                crashes++;
            } else {
                h.restartGracefully();
                h.server.getPluginManager().registerEvents(spy, h.plugin);
            }
        }

        // Calm down: storage works, inventories have room, everyone comes back, everything settles.
        for (TestPlayer p : players) p.saveSilentlyFails = false;
        for (TestPlayer p : players) if (!p.isOnline()) h.join(p);
        h.awaitNoRituals();
        h.settle();
        for (TestPlayer p : players) {
            for (int s = 0; s < 36; s++) {
                ItemStack it = p.getInventory().getItem(s);
                if (it != null && it.getType() == Material.DIRT) p.getInventory().setItem(s, null);
            }
            p.performCommand("vm claim");
        }
        for (int round = 0; round < 2; round++) {
            for (TestPlayer p : players) h.quit(p);
            for (TestPlayer p : players) h.join(p);
            h.settle();
            for (TestPlayer p : players) p.performCommand("vm claim");
        }

        Map<UUID, Integer> expected = new HashMap<>(initial);
        int counted = 0;
        for (Map.Entry<UUID, Committed> e : committed.entrySet()) {
            if (!durable.contains(e.getKey())) continue;
            counted++;
            Committed c = e.getValue();
            expected.merge(c.player(), c.reward() - c.input(), Integer::sum);
        }
        for (TestPlayer p : players) {
            assertEquals(expected.get(p.getUniqueId()), Harness.count(p, material.get(p.getUniqueId())),
                    "seed " + seed + ": " + p.getName() + " (" + counted + " counted rituals, " + crashes + " crashes)");
        }
        assertEquals(0, h.rt().journal().size(), "seed " + seed + ": every record settled: " + h.rt().journal().all());
        assertTrue(counted > 5, "seed " + seed + ": the chaos actually ran rituals (" + counted + ")");
        assertEquals(0, h.displayEntities());
        java.util.Set<UUID> leftovers = new HashSet<>();
        for (TestPlayer p : players) for (PlayerLedger.Entry e : PlayerLedger.decode(p.ledger()).entries()) leftovers.add(e.ritualId());
        assertEquals(java.util.Set.of(), leftovers, "seed " + seed + ": ledgers are clean");
    }
}
