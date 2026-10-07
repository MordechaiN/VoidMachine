/*
 * VoidMachine — a forbidden artifact for Paper servers.
 * Created by Mordechai Neeman. Licensed under the MIT License — see LICENSE.
 */
package com.voidmachine;

import com.voidmachine.paper.VoidMachineRuntime;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Plugin entry point. All wiring and lifecycle live in {@link VoidMachineRuntime}.
 * (Not final: MockBukkit proxies the plugin class in integration tests.)
 */
public class VoidMachinePlugin extends JavaPlugin {

    private VoidMachineRuntime runtime;

    @Override
    public void onEnable() {
        runtime = new VoidMachineRuntime(this);
        try {
            runtime.start();
        } catch (RuntimeException e) {
            // Fail closed: a half-started plugin must not accept offerings. Pending records stay on disk.
            getLogger().log(Level.SEVERE, "VoidMachine failed to start; disabling. No items are at risk: pending records stay in the journal.", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            try {
                runtime.stop();
            } catch (RuntimeException e) {
                getLogger().log(Level.SEVERE, "Error while stopping VoidMachine (pending records are reconciled at the next start)", e);
            }
        }
    }

    /** For tests and integrations inside this plugin. */
    public VoidMachineRuntime runtime() {
        return runtime;
    }
}
