/*
 * VoidMachine — a brutal item-sink ritual machine for Paper servers.
 *
 * Created by Mordechai Neeman <neeman2009@gmail.com>
 * https://github.com/MordechaiNeeman/VoidMachine
 *
 * Copyright (c) 2026 Mordechai Neeman.
 * Licensed under the MIT License — see LICENSE for details.
 */
package com.voidmachine.db;

import com.voidmachine.core.Outcome;

import java.time.Instant;
import java.util.UUID;

/**
 * A single completed sacrifice record. Immutable.
 */
public record HistoryEntry(
        long id,
        UUID playerId,
        String playerName,
        String materialKey,
        int inputAmount,
        int outputAmount,
        Outcome outcome,
        Instant when
) {
    public HistoryEntry {
        playerId = playerId == null ? new UUID(0L, 0L) : playerId;
        when = when == null ? Instant.now() : when;
    }
}
