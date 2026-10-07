package com.voidmachine.core.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;

import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts text with right-to-left scripts (Hebrew, Arabic) from logical order to visual order.
 *
 * <p>Java Edition clients run the Unicode bidi algorithm themselves and must receive logical text.
 * Bedrock clients (via Geyser) draw every string left-to-right, so Hebrew appears reversed unless the
 * server pre-orders it. This class performs that reordering on styled Adventure components: styles
 * are preserved per character, mirrored brackets are swapped inside RTL runs, and non-text components
 * (translatable item names, keybinds) are kept intact as left-to-right atoms.</p>
 */
public final class BidiText {

    private BidiText() {
    }

    /** Fast check: does the string contain any right-to-left character? */
    public static boolean containsRtl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0590 && c <= 0x08FF) return true; // Hebrew, Arabic, Syriac, Thaana, NKo...
            if (c >= 0xFB1D && c <= 0xFDFF) return true; // Hebrew & Arabic presentation forms
            if (c >= 0xFE70 && c <= 0xFEFF) return true;
        }
        return false;
    }

    /** Reorders a plain string (no styles). */
    public static String toVisual(String logical, boolean baseRtl) {
        if (!containsRtl(logical)) return logical;
        StringBuilder out = new StringBuilder(logical.length());
        String[] lines = logical.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            List<Unit> units = new ArrayList<>();
            lines[i].codePoints().forEach(cp -> units.add(Unit.text(cp, Style.empty())));
            for (Unit u : reorderLine(units, baseRtl)) out.appendCodePoint(u.codePoint);
        }
        return out.toString();
    }

    /** Reorders a styled component. Returns the input unchanged when it contains no RTL text. */
    public static Component toVisual(Component component, boolean baseRtl) {
        List<Unit> units = new ArrayList<>();
        flatten(component, Style.empty(), units);
        boolean anyRtl = false;
        for (Unit u : units) {
            if (u.atom == null && isRtlCodePoint(u.codePoint)) {
                anyRtl = true;
                break;
            }
        }
        if (!anyRtl) return component;

        List<Unit> visual = new ArrayList<>(units.size());
        List<Unit> line = new ArrayList<>();
        for (Unit u : units) {
            if (u.atom == null && u.codePoint == '\n') {
                visual.addAll(reorderLine(line, baseRtl));
                visual.add(u);
                line.clear();
            } else {
                line.add(u);
            }
        }
        visual.addAll(reorderLine(line, baseRtl));
        return rebuild(visual);
    }

    // ------------------------------------------------------------------------------------------

    private static final class Unit {
        final int codePoint;
        final Style style;
        final Component atom;

        private Unit(int codePoint, Style style, Component atom) {
            this.codePoint = codePoint;
            this.style = style;
            this.atom = atom;
        }

        static Unit text(int cp, Style style) {
            return new Unit(cp, style, null);
        }

        static Unit atom(Component c) {
            return new Unit('A', null, c); // strong left-to-right placeholder for the algorithm
        }
    }

    private static void flatten(Component c, Style inherited, List<Unit> out) {
        Style effective = inherited.merge(c.style());
        if (c instanceof TextComponent text) {
            text.content().codePoints().forEach(cp -> out.add(Unit.text(cp, effective)));
        } else {
            out.add(Unit.atom(c.children(List.of()).style(effective)));
        }
        for (Component child : c.children()) flatten(child, effective, out);
    }

    private static List<Unit> reorderLine(List<Unit> units, boolean baseRtl) {
        int n = units.size();
        if (n == 0) return List.of();
        StringBuilder sb = new StringBuilder(n);
        int[] firstChar = new int[n];
        for (int i = 0; i < n; i++) {
            firstChar[i] = sb.length();
            sb.appendCodePoint(units.get(i).codePoint);
        }
        Bidi bidi = new Bidi(sb.toString(), baseRtl ? Bidi.DIRECTION_RIGHT_TO_LEFT : Bidi.DIRECTION_LEFT_TO_RIGHT);
        byte[] levels = new byte[n];
        for (int i = 0; i < n; i++) levels[i] = (byte) bidi.getLevelAt(firstChar[i]);
        // Remember each unit's level before reordering; RTL units get mirrored brackets afterwards.
        java.util.IdentityHashMap<Unit, Byte> levelOf = new java.util.IdentityHashMap<>(n * 2);
        for (int i = 0; i < n; i++) levelOf.put(units.get(i), levels[i]);
        Unit[] arr = units.toArray(new Unit[0]);
        Bidi.reorderVisually(levels.clone(), 0, arr, 0, n);
        List<Unit> out = new ArrayList<>(n);
        for (Unit u : arr) {
            byte level = levelOf.get(u);
            if (u.atom == null && (level & 1) == 1) {
                int mirrored = mirror(u.codePoint);
                out.add(mirrored == u.codePoint ? u : Unit.text(mirrored, u.style));
            } else {
                out.add(u);
            }
        }
        return out;
    }

    private static Component rebuild(List<Unit> visual) {
        List<Component> parts = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        Style runStyle = null;
        for (Unit u : visual) {
            if (u.atom != null) {
                if (!run.isEmpty()) {
                    parts.add(Component.text(run.toString(), runStyle));
                    run.setLength(0);
                }
                runStyle = null;
                parts.add(u.atom);
                continue;
            }
            if (runStyle != null && !Objects.equals(runStyle, u.style) && !run.isEmpty()) {
                parts.add(Component.text(run.toString(), runStyle));
                run.setLength(0);
            }
            runStyle = u.style;
            run.appendCodePoint(u.codePoint);
        }
        if (!run.isEmpty()) parts.add(Component.text(run.toString(), runStyle));
        return Component.text().append(parts).build();
    }

    private static boolean isRtlCodePoint(int cp) {
        byte d = Character.getDirectionality(cp);
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
    }

    private static int mirror(int cp) {
        return switch (cp) {
            case '(' -> ')';
            case ')' -> '(';
            case '[' -> ']';
            case ']' -> '[';
            case '{' -> '}';
            case '}' -> '{';
            case '<' -> '>';
            case '>' -> '<';
            case '«' -> '»';
            case '»' -> '«';
            default -> cp;
        };
    }
}
