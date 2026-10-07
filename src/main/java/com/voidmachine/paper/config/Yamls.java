package com.voidmachine.paper.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Safe YAML reading (no arbitrary types) with line-numbered errors, and atomic writing. */
public final class Yamls {

    private Yamls() {
    }

    /** Parse error with the file and line, phrased for admins. */
    public static final class YamlSyntaxException extends IOException {
        public YamlSyntaxException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static Map<String, Object> read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.getFileName().toString());
    }

    public static Map<String, Object> parse(String text, String name) throws YamlSyntaxException {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(50);
            options.setAllowDuplicateKeys(false);
            Object o = new Yaml(new SafeConstructor(options)).load(text);
            if (o == null) return new LinkedHashMap<>();
            if (!(o instanceof Map<?, ?> m)) throw new YamlSyntaxException(name + ": the top level must be a section of settings", null);
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        } catch (MarkedYAMLException e) {
            String where = e.getProblemMark() == null ? "" : " at line " + (e.getProblemMark().getLine() + 1)
                    + ", column " + (e.getProblemMark().getColumn() + 1);
            throw new YamlSyntaxException(name + " is not valid YAML" + where + ": " + e.getProblem()
                    + " (check indentation: use spaces, not tabs, and quote values containing ':' or '#')", e);
        } catch (YAMLException e) {
            throw new YamlSyntaxException(name + " is not valid YAML: " + e.getMessage(), e);
        }
    }

    public static void writeAtomically(Path file, Map<String, Object> tree, String header) throws IOException {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setAllowUnicode(true);
        opts.setWidth(120);
        String body = new Yaml(new SafeConstructor(new LoaderOptions()), new Representer(opts), opts).dump(tree);
        String text = (header == null ? "" : "# " + header + System.lineSeparator()) + body;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
