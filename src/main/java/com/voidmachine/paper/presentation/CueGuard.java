package com.voidmachine.paper.presentation;

import com.voidmachine.core.health.HealthMonitor;
import com.voidmachine.core.script.Cue;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plays cues so that one failing effect (for example an effect a server fork does not support) never
 * stops a presentation: the cue is skipped and the failure is reported once per kind of cue.
 */
public final class CueGuard {

    private final Logger logger;
    private final HealthMonitor health;
    private final Set<String> reported = new HashSet<>();

    public CueGuard(Logger logger, HealthMonitor health) {
        this.logger = logger;
        this.health = health;
    }

    public void play(Cue cue, double progress, CuePlayer.Context ctx) {
        try {
            CuePlayer.play(cue, progress, ctx);
        } catch (RuntimeException e) {
            String kind = cue.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (reported.add(kind)) {
                logger.log(Level.WARNING, "A " + kind + " effect failed and is skipped whenever it fails (gameplay is unaffected)", e);
                health.raise(HealthMonitor.Source.PRESENTATION, kind + " effects fail on this server: " + e);
            }
        }
    }
}
