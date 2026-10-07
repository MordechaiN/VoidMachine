package com.voidmachine.core.config;

import java.util.ArrayList;
import java.util.List;

/** Collects problems while a configuration file is read. */
public final class Problems {

    private final String file;
    private final List<ConfigProblem> list = new ArrayList<>();

    public Problems(String file) {
        this.file = file;
    }

    public String file() {
        return file;
    }

    public void error(String path, String message, String expected, Object found, String hint) {
        list.add(new ConfigProblem(ConfigProblem.Severity.ERROR, file, path, message, expected, describe(found), hint));
    }

    public void error(String path, String message) {
        error(path, message, null, null, null);
    }

    public void warning(String path, String message, String expected, Object found, String hint) {
        list.add(new ConfigProblem(ConfigProblem.Severity.WARNING, file, path, message, expected, describe(found), hint));
    }

    public void warning(String path, String message) {
        warning(path, message, null, null, null);
    }

    public void addAll(List<ConfigProblem> problems) {
        list.addAll(problems);
    }

    public List<ConfigProblem> all() {
        return List.copyOf(list);
    }

    public List<ConfigProblem> errors() {
        return list.stream().filter(p -> p.severity() == ConfigProblem.Severity.ERROR).toList();
    }

    public List<ConfigProblem> warnings() {
        return list.stream().filter(p -> p.severity() == ConfigProblem.Severity.WARNING).toList();
    }

    public boolean hasErrors() {
        return list.stream().anyMatch(p -> p.severity() == ConfigProblem.Severity.ERROR);
    }

    static String describe(Object found) {
        if (found == null) return null;
        if (found instanceof String s) return "\"" + s + "\"";
        if (found instanceof java.util.Map<?, ?>) return "a section";
        if (found instanceof java.util.List<?> l) return "a list of " + l.size();
        return found + " (" + found.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT) + ")";
    }
}
