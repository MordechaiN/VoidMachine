package com.voidmachine.core.script;

import com.voidmachine.core.config.SettingsLoader;
import com.voidmachine.core.timeline.Pacing;

import java.util.Map;
import java.util.Optional;

/** Validated contents of {@code rituals.yml}: pacing presets and themes. */
public record Ritualbook(Map<String, Pacing> pacings, Map<String, Theme> themes) implements SettingsLoader.Catalog {

    public Ritualbook {
        pacings = Map.copyOf(pacings);
        themes = Map.copyOf(themes);
    }

    @Override
    public Optional<Pacing> pacing(String id) {
        return Optional.ofNullable(pacings.get(id));
    }

    @Override
    public boolean hasTheme(String id) {
        return themes.containsKey(id);
    }

    @Override
    public int longestJackpotHold(String themeId) {
        Theme t = themes.get(themeId);
        return t == null ? 0 : t.longestJackpotHold();
    }

    public Theme theme(String id) {
        Theme t = themes.get(id);
        if (t == null) throw new IllegalArgumentException("unknown theme " + id);
        return t;
    }
}
