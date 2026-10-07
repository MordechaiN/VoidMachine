package com.voidmachine.paper.presentation;

import com.voidmachine.paper.i18n.Messages;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The ritual's boss bars: one per rendering variant (language and Bedrock text order), each shown to
 * the viewers of that variant. Boss bars render identically on Java and Bedrock.
 */
public final class RitualBars {

    private final Messages messages;
    private final Map<Messages.Variant, BossBar> bars = new HashMap<>();
    private final Map<UUID, Messages.Variant> viewers = new HashMap<>();
    private String nameKey = "ritual.bar.awaken";
    private TagResolver[] resolvers = new TagResolver[0];
    private float progress;
    private BossBar.Color color = BossBar.Color.PURPLE;

    public RitualBars(Messages messages) {
        this.messages = messages;
    }

    /** Ensures exactly {@code wanted} see the bar. */
    public void sync(Iterable<Player> wanted) {
        Set<UUID> keep = new HashSet<>();
        for (Player p : wanted) {
            keep.add(p.getUniqueId());
            Messages.Variant v = messages.variant(p);
            Messages.Variant old = viewers.get(p.getUniqueId());
            if (v.equals(old)) continue;
            if (old != null) p.hideBossBar(bar(old));
            p.showBossBar(bar(v));
            viewers.put(p.getUniqueId(), v);
        }
        for (UUID id : Set.copyOf(viewers.keySet())) {
            if (keep.contains(id)) continue;
            Messages.Variant v = viewers.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(bar(v));
        }
    }

    public void name(String key, TagResolver... resolvers) {
        this.nameKey = key;
        this.resolvers = resolvers;
        bars.forEach((v, bar) -> bar.name(messages.render(v, key, resolvers)));
    }

    public void progress(float value) {
        this.progress = Math.max(0f, Math.min(1f, value));
        for (BossBar bar : bars.values()) bar.progress(progress);
    }

    public void color(String themeColor) {
        color(parse(themeColor));
    }

    public void color(BossBar.Color c) {
        this.color = c;
        for (BossBar bar : bars.values()) bar.color(c);
    }

    public void hideAll() {
        for (Map.Entry<UUID, Messages.Variant> e : viewers.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.hideBossBar(bar(e.getValue()));
        }
        viewers.clear();
    }

    public int viewerCount() {
        return viewers.size();
    }

    private BossBar bar(Messages.Variant v) {
        return bars.computeIfAbsent(v, k -> {
            Component name = messages.render(k, nameKey, resolvers);
            return BossBar.bossBar(name, progress, color, BossBar.Overlay.PROGRESS);
        });
    }

    public static BossBar.Color parse(String s) {
        try {
            return BossBar.Color.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BossBar.Color.PURPLE;
        }
    }
}
