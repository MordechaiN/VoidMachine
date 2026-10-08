package com.voidmachine.it;

import com.voidmachine.VoidMachinePlugin;
import com.voidmachine.core.machine.MachineRecord;
import com.voidmachine.paper.VoidMachineRuntime;
import com.voidmachine.paper.machine.Machine;
import com.voidmachine.paper.menu.OfferingMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Boots the real plugin on MockBukkit with a test configuration: four single-outcome profiles
 * (so verdicts are known in advance without any test hook in production code), no cooldowns,
 * and swift pacing.
 */
public final class Harness implements AutoCloseable {

    public static final String TEST_PROFILES = """
            profiles:
              t-double:
                weights: { doubled: 1 }
                theme: void
                pacing: swift
                permission: ""
              t-loss:
                weights: { consumed: 1 }
                theme: void
                pacing: swift
                permission: ""
              t-jackpot:
                weights: { jackpot: 1 }
                theme: void
                pacing: swift
                permission: ""
              t-tithe:
                weights: { tithe: 1 }
                theme: void
                pacing: swift
                permission: ""
            """;

    public final ServerMock server;
    public final TestWorld world;
    public final VoidMachinePlugin plugin;
    public final NamespacedKey ledgerKey;
    private final List<TestPlayer> players = new ArrayList<>();
    /** Everything the plugin logged at WARNING or above. */
    public final List<LogRecord> problems = new CopyOnWriteArrayList<>();

    public Harness() {
        this(UnaryOperator.identity());
    }

    /** @param configEdit applied to the bundled config.yml (after the test profiles are added) */
    public Harness(UnaryOperator<String> configEdit) {
        server = MockBukkit.mock(new VmServerMock());
        try {
            world = new TestWorld(server, "world");
            server.addWorld(world);
            plugin = MockBukkit.load(VoidMachinePlugin.class);
            ledgerKey = new NamespacedKey(plugin, "ledger");
            plugin.getLogger().addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getLevel().intValue() >= Level.WARNING.intValue()) problems.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
            Path config = dataDir().resolve("config.yml");
            String text = Files.readString(config)
                    .replace("profiles:\n", TEST_PROFILES)
                    .replace("player-seconds: 8", "player-seconds: 0")
                    .replace("interact-cooldown-ms: 400", "interact-cooldown-ms: 0");
            Files.writeString(config, configEdit.apply(text));
            restartGracefully();
        } catch (IOException | RuntimeException | Error e) {
            MockBukkit.unmock();
            throw e instanceof IOException io ? new IllegalStateException(io) : sneaky(e);
        }
    }

    private static RuntimeException sneaky(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error err) throw err;
        return new IllegalStateException(t);
    }

    public VoidMachineRuntime rt() {
        return plugin.runtime();
    }

    public Path dataDir() {
        return plugin.getDataFolder().toPath();
    }

    // ------------------------------------------------------------------------------------------
    // Time
    // ------------------------------------------------------------------------------------------

    public void ticks(int n) {
        for (int i = 0; i < n; i++) server.getScheduler().performOneTick();
    }

    /** Ticks (giving the journal I/O thread time) until the condition holds. */
    public void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: " + what);
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
            server.getScheduler().performOneTick();
        }
    }

    /** Lets queued journal I/O complete and its main-thread callbacks run. */
    public void settle() {
        for (int i = 0; i < 20; i++) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            server.getScheduler().performOneTick();
        }
    }

    public void awaitNoRituals() {
        await(() -> rt().rituals().active().isEmpty(), "all rituals to finish");
    }

    // ------------------------------------------------------------------------------------------
    // World
    // ------------------------------------------------------------------------------------------

    public Machine machine(String id, String profile, int x, int y, int z) {
        world.loadChunk(x >> 4, z >> 4);
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.RESPAWN_ANCHOR);
        MachineRecord record = new MachineRecord(id, id, world.getName(), x, y, z, profile, true, System.currentTimeMillis(), "test");
        Machine m = new Machine(record);
        if (!rt().machines().add(m)) fail("could not add machine " + id);
        try {
            rt().saveMachines();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return m;
    }

    public TestPlayer player(String name, Machine near) {
        return player(new TestPlayer(server, name, ledgerKey), near);
    }

    public TestPlayer player(TestPlayer p, Machine near) {
        server.addPlayer(p);
        p.teleport(near.location().clone().add(0.5, 1, 2.5));
        players.add(p);
        return p;
    }

    public void interact(Player p, Machine m) {
        PlayerInteractEvent event = new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, p.getInventory().getItemInMainHand(),
                m.location().getBlock(), BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
    }

    public static boolean hasMenuOpen(TestPlayer p) {
        var top = p.getOpenInventory().getTopInventory();
        return top != null && top.getHolder(false) instanceof OfferingMenu;
    }

    public OfferingMenu openMenu(TestPlayer p) {
        Object holder = p.getOpenInventory().getTopInventory().getHolder(false);
        if (holder instanceof OfferingMenu menu) return menu;
        fail("no offering menu open for " + p.getName() + " (top inventory holder: " + holder + ")");
        return null;
    }

    /** A click as the server reports it; returns the event so tests can check it was cancelled. */
    public InventoryClickEvent click(TestPlayer p, int rawSlot, ClickType type) {
        InventoryView view = p.getOpenInventory();
        int top = view.getTopInventory().getSize();
        InventoryType.SlotType slotType = rawSlot >= top + 27 ? InventoryType.SlotType.QUICKBAR : InventoryType.SlotType.CONTAINER;
        InventoryClickEvent event = new InventoryClickEvent(view, slotType, rawSlot, type, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);
        return event;
    }

    public InventoryClickEvent clickTop(TestPlayer p, int rawSlot) {
        return click(p, rawSlot, ClickType.LEFT);
    }

    /** Taps a slot of the player's own inventory (0-35) while a 27-slot menu is open. */
    public InventoryClickEvent clickOwn(TestPlayer p, int playerSlot) {
        int raw = playerSlot < 9 ? 27 + 27 + playerSlot : 27 + (playerSlot - 9);
        return click(p, raw, ClickType.LEFT);
    }

    /** Opens the machine, keeps the preselected held stack and presses START. */
    public void offerHeld(TestPlayer p, Machine m) {
        interact(p, m);
        openMenu(p);
        clickTop(p, 22);
    }

    /** Drains the chat messages the player received, as plain text. */
    public static List<String> messages(TestPlayer p) {
        List<String> out = new ArrayList<>();
        Component c;
        while ((c = p.nextComponentMessage()) != null) out.add(PlainTextComponentSerializer.plainText().serialize(c));
        return out;
    }

    /** Fails if a rendered text still contains a MiniMessage tag (an unresolved placeholder or a typo). */
    public static void assertRendered(List<String> texts) {
        for (String t : texts) {
            if (RAW_TAG.matcher(t).find()) fail("unrendered tag in: " + t);
        }
    }

    private static final java.util.regex.Pattern RAW_TAG = java.util.regex.Pattern.compile("<[a-z_#/:!-][^<>]*>");

    /** Drains the action bar texts the player received, as plain text. */
    public static List<String> actionBars(TestPlayer p) {
        List<String> out = new ArrayList<>();
        Component c;
        while ((c = p.nextActionBar()) != null) out.add(PlainTextComponentSerializer.plainText().serialize(c));
        return out;
    }

    /** Gives a stack in slot 0 and selects it. */
    public static void hold(TestPlayer p, Material material, int amount) {
        p.getInventory().setItem(0, new ItemStack(material, amount));
        p.getInventory().setHeldItemSlot(0);
    }

    public static int count(Player p, Material material) {
        int n = 0;
        for (ItemStack s : p.getInventory().getContents()) {
            if (s != null && s.getType() == material) n += s.getAmount();
        }
        return n;
    }

    /** Events of a type fired so far. */
    public <T extends org.bukkit.event.Event> java.util.stream.Stream<T> fired(Class<T> type) {
        return server.getPluginManager().getFiredEvents().filter(type::isInstance).map(type::cast);
    }

    public long displayEntities() {
        return world.getEntities().stream().filter(e -> e instanceof ItemDisplay).count();
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    /** Player quits: Paper saves their data on the way out. */
    public void quit(TestPlayer p) {
        p.saveData();
        p.disconnect();
    }

    /** Player joins: the server loads their data file. */
    public void join(TestPlayer p) {
        p.loadSaved();
        p.reconnect();
    }

    public void stop() {
        server.getPluginManager().disablePlugin(plugin);
    }

    public void start() {
        server.getPluginManager().enablePlugin(plugin);
    }

    public void restartGracefully() {
        server.getPluginManager().disablePlugin(plugin);
        server.getPluginManager().enablePlugin(plugin);
    }

    /**
     * Simulates a crash: no shutdown logic runs, unsaved player state is lost, non-persistent
     * entities vanish. Journal writes already handed to the I/O thread are allowed to finish (they
     * were on their way to disk). Then a fresh runtime starts and the players rejoin.
     */
    public void crashAndRestart() {
        VoidMachineRuntime old = rt();
        HandlerList.unregisterAll(plugin);
        old.journal().drain(5_000);
        // Callbacks the I/O thread queued for the main thread die with the old process.
        server.getScheduler().cancelTasks(plugin);
        old.audit().stop();
        List<TestPlayer> online = players.stream().filter(Player::isOnline).toList();
        for (TestPlayer p : online) p.disconnect();
        for (Entity e : List.copyOf(world.getEntities())) {
            if (!(e instanceof Player) && !e.isPersistent()) e.remove();
        }
        VoidMachineRuntime fresh = new VoidMachineRuntime(plugin);
        fresh.start();
        setRuntime(fresh);
        for (TestPlayer p : online) join(p);
    }

    private void setRuntime(VoidMachineRuntime runtime) {
        try {
            Field f = VoidMachinePlugin.class.getDeclaredField("runtime");
            f.setAccessible(true);
            f.set(plugin, runtime);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public Location spawnNear(Machine m, double dx, double dz) {
        return m.location().clone().add(dx, 1, dz);
    }

    /** Fails if the plugin logged a warning or error (e.g. a presentation failure that resolved a ritual early). */
    public void assertNoProblemsLogged() {
        if (problems.isEmpty()) return;
        StringBuilder sb = new StringBuilder("unexpected warnings/errors:");
        for (LogRecord r : problems) {
            sb.append("\n  ").append(r.getLevel()).append(' ').append(r.getMessage());
            if (r.getThrown() != null) {
                sb.append(" — ").append(r.getThrown());
                StackTraceElement[] st = r.getThrown().getStackTrace();
                for (int i = 0; i < Math.min(6, st.length); i++) sb.append("\n      at ").append(st[i]);
            }
        }
        fail(sb.toString());
    }

    @Override
    public void close() {
        MockBukkit.unmock();
    }
}
