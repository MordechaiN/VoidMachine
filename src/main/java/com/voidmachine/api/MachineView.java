package com.voidmachine.api;

/** Immutable description of a machine for other plugins. */
public record MachineView(String id, String displayName, String world, int x, int y, int z, String profile, boolean enabled) {
}
