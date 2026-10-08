package com.voidmachine.paper.command;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Two-step confirmation for destructive admin actions: the first command describes what will happen
 * and issues a short code; only {@code /vm admin confirm <code>} from the same sender within 30 seconds
 * performs it.
 */
public final class Confirmations {

    private static final long TTL_MILLIS = 30_000;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private record Pending(String sender, String description, Runnable action, long expires) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new HashMap<>();

    public String request(String sender, String description, Runnable action) {
        purge();
        pending.values().removeIf(p -> p.sender().equals(sender));
        String code;
        do {
            StringBuilder sb = new StringBuilder(5);
            for (int i = 0; i < 5; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            code = sb.toString();
        } while (pending.containsKey(code));
        pending.put(code, new Pending(sender, description, action, System.currentTimeMillis() + TTL_MILLIS));
        return code;
    }

    /** Runs and returns the description, or {@code null} if the code is unknown, expired or someone else's. */
    public String confirm(String sender, String code) {
        purge();
        Pending p = pending.get(code.toUpperCase(java.util.Locale.ROOT));
        if (p == null || !p.sender().equals(sender)) return null;
        pending.remove(code.toUpperCase(java.util.Locale.ROOT));
        p.action().run();
        return p.description();
    }

    private void purge() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> p.expires() < now);
    }
}
