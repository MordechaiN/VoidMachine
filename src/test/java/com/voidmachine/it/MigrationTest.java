package com.voidmachine.it;

import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.journal.RecordKind;
import com.voidmachine.paper.machine.Machine;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A V1 installation with in-flight data is upgraded in place, once, without losing or duplicating items. */
class MigrationTest {

    private Harness h;

    @AfterEach
    void tearDown() {
        if (h != null) h.close();
    }

    private static byte[] v1Checkpoint(int state, UUID player, String name, ItemStack stack, int outcome, int output) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(2);
            out.writeByte(state);
            out.writeUTF(player.toString());
            out.writeUTF(name);
            out.writeUTF("world:100:64:-200");
            out.writeUTF("default");
            out.writeLong(1_700_000_000_000L);
            byte[] item = stack.serializeAsBytes();
            out.writeInt(item.length);
            out.write(item);
            out.writeByte(outcome);
            out.writeInt(output);
        }
        return bytes.toByteArray();
    }

    private static void copyResource(String resource, Path target) throws IOException {
        try (InputStream in = MigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            Files.write(target, in.readAllBytes());
        }
    }

    private static void wipe(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(dir)) Files.delete(p);
            }
        }
    }

    @Test
    void v1InstallationIsUpgradedOnceAndPaysExactly() throws Exception {
        h = new Harness();
        h.stop();
        Path data = h.dataDir();
        wipe(data);
        copyResource("v1/config.yml", data.resolve("config.yml"));
        copyResource("v1/machines.yml", data.resolve("machines.yml"));
        Files.writeString(data.resolve("messages.yml"), "prefix: '[V1]'\n");

        UUID pat = UUID.nameUUIDFromBytes("test-player:Pat".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path checkpoints = Files.createDirectories(data.resolve("checkpoints"));
        // ANIMATING: the item was taken, the outcome never persisted -> refunded.
        Files.write(checkpoints.resolve("tx-1.dat"), v1Checkpoint(2, pat, "Pat", new ItemStack(Material.DIAMOND, 10), 0, 0));
        // DELIVERING: V1 may already have paid -> held for an admin decision.
        Files.write(checkpoints.resolve("tx-2.dat"), v1Checkpoint(3, pat, "Pat", new ItemStack(Material.EMERALD, 4), 2, 8));
        Files.writeString(data.resolve("pending_deliveries.yml"), """
                pending:
                  %s:
                    - item: %s
                      reason: OVERFLOW
                      name: Pat
                      epoch: 1700000000001
                """.formatted(pat, Base64.getEncoder().encodeToString(new ItemStack(Material.GOLD_INGOT, 3).serializeAsBytes())));
        Files.writeString(data.resolve("global_stats.yml"), """
                total-sacrifices: 120
                destroyed: 80
                returned: 25
                doubled: 10
                tripled: 4
                jackpots: 1
                items-consumed: 900
                """);

        h.start();
        assertNotNull(h.rt().settings(), "the V1 config was migrated to a valid V2 config");
        assertTrue(Files.readString(data.resolve("config.yml")).contains("config-version: 2"));
        assertTrue(Files.exists(data.resolve("backup/v1/config.yml")), "the V1 config is kept as a backup");
        assertTrue(Files.exists(data.resolve("backup/v1/messages.yml")));
        assertTrue(Files.exists(data.resolve("backup/v1/checkpoints/tx-1.dat")));
        assertFalse(Files.exists(data.resolve("checkpoints")));
        assertTrue(Files.exists(data.resolve(".v1-migrated")));
        assertNotNull(h.rt().machines().byId("main_machine"));
        assertNotNull(h.rt().machines().byId("dark_altar"));
        assertEquals(120, h.rt().stats().global().rituals());

        List<JournalRecord> records = List.copyOf(h.rt().journal().all());
        assertEquals(3, records.size());
        assertEquals(2, records.stream().filter(r -> r.kind() == RecordKind.CLAIM).count());
        assertEquals(1, records.stream().filter(r -> r.kind() == RecordKind.REVIEW).count());

        Machine m = h.rt().machines().byId("main_machine");
        TestPlayer p = h.player("Pat", m);
        h.settle();
        assertEquals(pat, p.getUniqueId());
        assertEquals(10, Harness.count(p, Material.DIAMOND), "interrupted V1 ritual refunded");
        assertEquals(3, Harness.count(p, Material.GOLD_INGOT), "V1 pending delivery paid");
        assertEquals(0, Harness.count(p, Material.EMERALD), "ambiguous V1 delivery waits for an admin");

        h.quit(p);
        h.restartGracefully();
        h.join(p);
        h.settle();
        assertEquals(10, Harness.count(p, Material.DIAMOND), "never paid twice, also not after a restart");
        assertEquals(3, Harness.count(p, Material.GOLD_INGOT));
        assertEquals(1, h.rt().journal().size(), "only the admin review remains");
        assertEquals(120, h.rt().stats().global().rituals(), "statistics imported once");
    }
}
