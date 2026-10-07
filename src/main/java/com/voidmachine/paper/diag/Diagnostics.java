package com.voidmachine.paper.diag;

import com.voidmachine.core.health.HealthMonitor;
import com.voidmachine.core.journal.JournalRecord;
import com.voidmachine.core.journal.RecordKind;
import com.voidmachine.paper.VoidMachineRuntime;
import com.voidmachine.paper.machine.Machine;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /vm admin diagnostics}: everything needed to troubleshoot, in one screen. */
public final class Diagnostics {

    private Diagnostics() {
    }

    public static List<String> report(VoidMachineRuntime rt) {
        List<String> out = new ArrayList<>();
        Runtime jvm = Runtime.getRuntime();
        long used = (jvm.totalMemory() - jvm.freeMemory()) / (1024 * 1024);
        long max = jvm.maxMemory() / (1024 * 1024);
        out.add("VoidMachine " + rt.plugin().getPluginMeta().getVersion() + " on " + Bukkit.getName() + " " + Bukkit.getVersion());
        out.add(" Minecraft " + Bukkit.getMinecraftVersion() + ", Java " + System.getProperty("java.version")
                + ", memory " + used + "/" + max + " MB");
        out.add(" Geyser: " + present("Geyser-Spigot") + ", Floodgate: " + present("floodgate") + ", Bedrock detection: " + rt.bedrock().source());
        long dormant = rt.machines().all().stream().filter(m -> m.world() == null).count();
        long disabled = rt.machines().all().stream().filter(m -> !m.record().enabled()).count();
        long loaded = rt.machines().all().stream().filter(Machine::isChunkLoaded).count();
        out.add(" Machines: " + rt.machines().size() + " (" + loaded + " loaded, " + dormant + " dormant, " + disabled + " disabled)");
        out.add(" Rituals: " + rt.rituals().active().size() + " running, " + rt.rituals().completed() + " completed, "
                + rt.rituals().aborted() + " ended before taking anything (this session)");
        List<JournalRecord> all = new ArrayList<>(rt.journal().all());
        long reviews = all.stream().filter(r -> r.kind() == RecordKind.REVIEW).count();
        out.add(" Journal: " + all.size() + " record(s) (" + reviews + " awaiting admin review), "
                + rt.journal().journal().listQuarantine().size() + " quarantined");
        out.add(" Payouts this session: " + rt.custody().paidThisSession() + " item(s), recoveries: " + rt.custody().recoveriesThisSession());
        TickProfiler d = rt.director().profiler();
        out.add(String.format(Locale.ROOT, " Ritual director: %d running, tick avg %.3f ms, p99 %.3f ms, max %.3f ms (%d ticks)",
                rt.director().running(), d.averageMillis(), d.percentileMillis(0.99), d.maxMillis(), d.runs()));
        TickProfiler a = rt.ambient().profiler();
        out.add(String.format(Locale.ROOT, " Ambient: %d machine(s) animated, tick avg %.3f ms, max %.3f ms",
                rt.ambient().animatedMachines(), a.averageMillis(), a.maxMillis()));
        out.add(" Displays tracked: " + rt.displays().tracked() + ", boss bar viewers: " + rt.director().bossbarViewers());
        out.add(" Effect budget: " + rt.budget().maxParticles() + " particles/" + rt.budget().maxPackets() + " packets per tick; thinned so far: "
                + rt.budget().deniedParticles() + " particles, " + rt.budget().deniedPackets() + " packets");
        out.add(" Audit: " + rt.audit().written() + " written, " + rt.audit().dropped() + " dropped");
        out.add(" Configuration: " + (rt.settings() == null ? "INVALID" : "valid") + ", languages " + rt.messages().languages());
        HealthMonitor h = rt.health();
        out.add(" Health: " + h.state() + (h.acceptsRituals() ? " (accepting offerings)" : " (offerings refused)"));
        for (HealthMonitor.Problem p : h.problems()) out.add("  - " + p.source() + ": " + p.detail());
        List<String> errors = h.recentErrors();
        if (!errors.isEmpty()) out.add(" Last error: " + errors.getLast());
        List<String> alerts = rt.custody().recoveryAlerts();
        if (!alerts.isEmpty()) out.add(" Last recovery alert: " + alerts.getLast());
        return out;
    }

    private static String present(String plugin) {
        var p = Bukkit.getPluginManager().getPlugin(plugin);
        return p == null ? "no" : (p.isEnabled() ? "yes" : "disabled");
    }
}
