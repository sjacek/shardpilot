package io.github.sjacek.shardpilot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

/**
 * Versioned UTF-8 YAML manifests with binary class names, independent of test framework.
 * Names are derived from file paths without loading bytecode or initializing consumer classes.
 * Readers validate the schema and reject malformed/duplicate class names instead of repairing evidence.
 */
public final class ClassManifest {
    private static final String SCHEMA_VERSION = "schemaVersion";
    private static final String CLASSES = "classes";
    private static final int VERSION = 1;
    private ClassManifest() { }

    /**
     * Resolves class-file paths relative to compilation roots, including default-package and nested names.
     * File contents are deliberately not inspected; the owning source-set tasks provide compiled inputs.
     * Duplicate binary names across roots fail rather than letting classpath order hide a collision.
     * @param files candidate class files
     * @param directories compilation roots
     * @return sorted binary names
     */
    public static Set<String> compiled(Collection<File> files, Collection<File> directories) {
        List<Path> roots = directories.stream().map(file -> file.toPath().toAbsolutePath().normalize()).toList();
        var names = new TreeSet<String>();
        for (File file : files) {
            Path path = file.toPath().toAbsolutePath().normalize();
            Path root = roots.stream().filter(path::startsWith).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Test candidate is outside configured class directories"));
            String relative = root.relativize(path).toString().replace('\\', '/');
            if (!relative.endsWith(".class")) throw new IllegalArgumentException("Unexpected non-class test candidate");
            String name = relative.substring(0, relative.length() - 6).replace('/', '.');
            validateName(name);
            if (!names.add(name)) throw new IllegalArgumentException("Duplicate compiled class: " + name);
        }
        return names;
    }

    /**
     * Reads one schemaVersion=1 mapping with a string sequence. Empty sequences require opt-in;
     * callers always read the baseline with allowEmpty=false. Unknown fields and duplicate keys fail.
     * @param file YAML document
     * @param allowEmpty whether an empty classes sequence is accepted
     * @return sorted, unique binary names
     * @throws IOException if the document cannot be read
     */
    public static Set<String> read(Path file, boolean allowEmpty) throws IOException {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Missing class manifest: " + file.getFileName());
        Object document;
        var settings = LoadSettings.builder().setAllowDuplicateKeys(false).setMaxAliasesForCollections(0).build();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            document = new Load(settings).loadFromReader(reader);
        } catch (YamlEngineException invalid) {
            throw new IllegalArgumentException("Invalid YAML manifest: " + file.getFileName(), invalid);
        }
        if (!(document instanceof Map<?, ?> fields) || !fields.keySet().equals(Set.of(SCHEMA_VERSION, CLASSES))) {
            throw new IllegalArgumentException("Manifest must contain only schemaVersion and classes: " + file.getFileName());
        }
        if (!(fields.get(SCHEMA_VERSION) instanceof Integer version) || version != VERSION) {
            throw new IllegalArgumentException("Unsupported manifest schemaVersion: " + file.getFileName());
        }
        if (!(fields.get(CLASSES) instanceof List<?> classes)) {
            throw new IllegalArgumentException("Manifest classes must be a sequence: " + file.getFileName());
        }
        var names = new TreeSet<String>();
        for (Object value : classes) {
            if (!(value instanceof String name)) {
                throw new IllegalArgumentException("Manifest classes must contain strings: " + file.getFileName());
            }
            validateName(name);
            if (!names.add(name)) throw new IllegalArgumentException("Duplicate class in " + file.getFileName() + ": " + name);
        }
        if (!allowEmpty && names.isEmpty()) throw new IllegalArgumentException("Empty class manifest: " + file.getFileName());
        return names;
    }

    /**
     * Writes ordered schema fields and sorted names; an empty shard is represented by classes: [].
     * @param file output document
     * @param names binary names supplied by a validated candidate set
     * @throws IOException if the document cannot be written
     */
    public static void write(Path file, Set<String> names) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        var document = new LinkedHashMap<String, Object>();
        document.put(SCHEMA_VERSION, VERSION);
        document.put(CLASSES, new ArrayList<>(new TreeSet<>(names)));
        var settings = DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK)
                .setIndent(2).setIndicatorIndent(2).setIndentWithIndicator(true).setSplitLines(false).build();
        Files.writeString(file, new Dump(settings).dumpToString(document), StandardCharsets.UTF_8);
    }

    private static void validateName(String name) {
        if (!name.matches("\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(?:\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*")) {
            throw new IllegalArgumentException("Invalid binary class name: " + name);
        }
    }
}
