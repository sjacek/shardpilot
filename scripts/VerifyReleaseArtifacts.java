import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Java 17 release-bundle gate; no Maven/Gradle runtime or publishing credentials are needed.
 * Validates local bytes and effective metadata before signatures are cryptographically checked
 * by GnuPG in verify-maven-release.sh. This utility never uploads a component.
 */
public final class VerifyReleaseArtifacts {
    private static final String GROUP_PATH = "io/github/sjacek/";
    private static final String PROJECT_URL = "https://github.com/sjacek/shardpilot";
    private static final List<String> ARTIFACTS = List.of("shardpilot-parent", "shardpilot-core", "shardpilot-maven-plugin");
    private static final Map<String, String> HASHES = Map.of("md5", "MD5", "sha1", "SHA-1", "sha256", "SHA-256", "sha512", "SHA-512");
    private static final int MAX_ENTRY_BYTES = 32 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;

    private VerifyReleaseArtifacts() { }

    /** Checks a repository-root release and extracts only validated paths into an empty directory. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: VerifyReleaseArtifacts.java REPOSITORY_ROOT EMPTY_EXTRACTION_DIRECTORY");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        String version = text(xml(Files.readAllBytes(root.resolve("pom.xml"))).getDocumentElement(), "version");
        Path bundle = root.resolve("target/central-publishing/central-bundle.zip");
        var entries = readArchive(Files.readAllBytes(bundle));
        verify(entries, version, Files.readAllBytes(root.resolve("build/release-verification/effective-pom.xml")));
        Path destination = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(destination)) throw new IllegalArgumentException("Extraction directory must not be a symbolic link");
        if (Files.exists(destination)) {
            try (var files = Files.list(destination)) {
                if (files.findAny().isPresent()) throw new IllegalArgumentException("Extraction directory must be empty");
            }
        }
        for (var entry : entries.entrySet()) {
            Path file = destination.resolve(entry.getKey()).normalize();
            if (!file.startsWith(destination)) throw new IllegalArgumentException("Archive path escapes extraction directory");
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        System.out.println("{\"status\":\"passed\",\"version\":\"" + version
                + "\",\"artifacts\":9,\"signatures\":9,\"entries\":" + entries.size() + "}");
    }

    /** Verifies an exact parent/core/adapter cohort, including every required attachment and checksum. */
    static void verify(Map<String, byte[]> entries, String version, byte[] effectivePom) throws Exception {
        require(version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"), "Release version must be stable MAJOR.MINOR.PATCH");
        Map<String, Element> models = verifyModels(effectivePom, version);
        var expected = new TreeSet<String>();
        for (String artifact : ARTIFACTS) {
            String prefix = GROUP_PATH + artifact + "/" + version + "/" + artifact + "-" + version;
            List<String> extensions = artifact.equals("shardpilot-parent") ? List.of(".pom")
                    : List.of(".pom", ".jar", "-sources.jar", "-javadoc.jar");
            for (String extension : extensions) {
                String file = prefix + extension;
                byte[] bytes = required(entries, file);
                expected.add(file);
                String signature = file + ".asc";
                require(new String(required(entries, signature), StandardCharsets.US_ASCII)
                        .startsWith("-----BEGIN PGP SIGNATURE-----"), "Missing armored PGP signature: " + signature);
                expected.add(signature);
                verifyHashes(entries, expected, file, true);
                verifyHashes(entries, expected, signature, false);
                if (extension.equals(".pom")) verifyPublishedPom(bytes, artifact, version, models.get(artifact));
            }
            if (!artifact.equals("shardpilot-parent")) verifyJars(entries, prefix, artifact, version);
        }
        require(entries.keySet().equals(expected), "Bundle contains unexpected files outside the release cohort");
    }

    private static Map<String, Element> verifyModels(byte[] bytes, String version) throws Exception {
        var models = new LinkedHashMap<String, Element>();
        var root = xml(bytes).getDocumentElement();
        for (Element project : children(root, "project")) {
            String artifact = text(project, "artifactId");
            require(models.put(artifact, project) == null, "Duplicate effective project");
            require(text(project, "groupId").equals("io.github.sjacek") && text(project, "version").equals(version), "Effective coordinates differ from release");
            require(!text(project, "name").isBlank() && !text(project, "description").isBlank(), "Missing project name/description");
            require(text(project, "url").equals(PROJECT_URL), "Incorrect inherited project URL");
            Element scm = child(project, "scm");
            require(text(scm, "url").equals(PROJECT_URL), "Incorrect inherited SCM URL");
            require(text(scm, "connection").equals("scm:git:" + PROJECT_URL + ".git"), "Incorrect inherited SCM connection");
            require(text(scm, "developerConnection").equals("scm:git:ssh://git@github.com/sjacek/shardpilot.git"), "Incorrect inherited developer connection");
            require(text(scm, "tag").equals("v" + version), "SCM tag differs from release version");
            Element developer = child(child(project, "developers"), "developer");
            require(!text(developer, "name").isBlank() && text(developer, "url").startsWith("https://"), "Missing developer metadata");
            Element license = child(child(project, "licenses"), "license");
            require(text(license, "name").equals("MIT License") && text(license, "url").startsWith("https://"), "Missing MIT license metadata");
        }
        require(models.keySet().equals(Set.copyOf(ARTIFACTS)), "Effective reactor must contain exactly parent/core/Maven adapter");
        return models;
    }

    private static void verifyPublishedPom(byte[] bytes, String artifact, String version, Element effective) throws Exception {
        Element pom = xml(bytes).getDocumentElement();
        require(text(pom, "artifactId").equals(artifact), "Published artifactId differs from bundle path");
        Element coordinates = artifact.equals("shardpilot-parent") ? pom : child(pom, "parent");
        require(text(coordinates, "groupId").equals("io.github.sjacek") && text(coordinates, "version").equals(version), "Published coordinates differ from release");
        require(text(pom, "name").equals(text(effective, "name")), "Published name differs from effective model");
        require(text(pom, "description").equals(text(effective, "description")), "Published description differs from effective model");
        // Artifact listings may read the uploaded POM without resolving its parent model.
        require(children(pom, "url").size() == 1 && text(pom, "url").equals(PROJECT_URL),
                "Published project homepage is missing or incorrect: " + artifact);
        if (!artifact.equals("shardpilot-parent")) {
            require(text(coordinates, "artifactId").equals("shardpilot-parent"), "Published parent is missing from cohort");
        } else {
            require(pom.getAttribute("child.project.url.inherit.append.path").equals("false"), "Disable project URL path appending in parent");
            Element scm = child(pom, "scm");
            for (String field : List.of("connection", "developerConnection", "url")) {
                require(scm.getAttribute("child.scm." + field + ".inherit.append.path").equals("false"), "Disable SCM path appending in parent");
            }
        }
    }

    private static void verifyJars(Map<String, byte[]> entries, String prefix, String artifact, String version) throws Exception {
        boolean core = artifact.equals("shardpilot-core");
        String type = core ? "io/github/sjacek/shardpilot/ShardPlan" : "io/github/sjacek/shardpilot/maven/SelectMojo";
        Map<String, byte[]> binary = readArchive(required(entries, prefix + ".jar"));
        require(binary.containsKey(type + ".class") && binary.containsKey("META-INF/LICENSE"), "Missing implementation or license in binary JAR");
        for (var entry : binary.entrySet()) {
            if (entry.getKey().endsWith(".class")) {
                byte[] code = entry.getValue();
                require(code.length >= 8 && (code[6] & 255) == 0 && (code[7] & 255) == 61, "Plugin bytecode must target Java 17");
            }
        }
        require(readArchive(required(entries, prefix + "-sources.jar")).containsKey(type + ".java"), "Missing main sources attachment");
        Map<String, byte[]> javadoc = readArchive(required(entries, prefix + "-javadoc.jar"));
        require(javadoc.containsKey("index.html") && javadoc.containsKey(type + ".html"), "Missing public API Javadoc attachment");
        if (!core) {
            Element descriptor = xml(required(binary, "META-INF/maven/plugin.xml")).getDocumentElement();
            require(text(descriptor, "version").equals(version) && text(descriptor, "goalPrefix").equals("shardpilot"), "Incorrect Maven plugin descriptor");
            var goals = new TreeSet<String>();
            for (Element mojo : children(child(descriptor, "mojos"), "mojo")) goals.add(text(mojo, "goal"));
            require(goals.equals(Set.of("prepare", "select", "verify", "refresh-durations")), "Missing Maven plugin goals");
        }
    }

    private static void verifyHashes(Map<String, byte[]> entries, Set<String> expected, String file, boolean required) throws Exception {
        for (var algorithm : HASHES.entrySet()) {
            String name = file + "." + algorithm.getKey();
            if (!required && !entries.containsKey(name)) continue;
            String actual = new String(required(entries, name), StandardCharsets.US_ASCII).trim();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm.getValue()).digest(entries.get(file)));
            require(digest.equalsIgnoreCase(actual), "Checksum mismatch: " + name);
            expected.add(name);
        }
    }

    /** Reads archives without resolving paths or following symlinks; limits apply to expanded bytes. */
    static Map<String, byte[]> readArchive(byte[] bytes) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        int total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                // Some Windows ZIP writers use host separators in local headers. Normalize before
                // validating paths and detecting collisions; backslash traversal is still rejected.
                String name = entry.getName().replace('\\', '/');
                require(!name.startsWith("/") && name.matches("[A-Za-z0-9_.$/-]+")
                        && !List.of(name.split("/")).contains("..") && !List.of(name.split("/")).contains("."), "Unsafe archive path");
                if (entry.isDirectory() || name.endsWith("/")) continue;
                byte[] contents = zip.readNBytes(MAX_ENTRY_BYTES + 1);
                total += contents.length;
                require(contents.length <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES && result.size() < 2000, "Archive exceeds release limits");
                require(result.put(name, contents) == null, "Duplicate archive entry");
            }
        }
        require(!result.isEmpty(), "Empty or invalid archive");
        return result;
    }

    static Document xml(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        // Keep malformed metadata fatal on every supported JDK without parser diagnostics on stderr.
        builder.setErrorHandler(new ErrorHandler() {
            @Override public void warning(SAXParseException exception) { /* Nonfatal parser warnings are permitted. */ }
            @Override public void error(SAXParseException exception) throws SAXException { throw exception; }
            @Override public void fatalError(SAXParseException exception) throws SAXException { error(exception); }
        });
        return builder.parse(new InputSource(new StringReader(new String(bytes, StandardCharsets.UTF_8))));
    }

    private static byte[] required(Map<String, byte[]> entries, String name) {
        byte[] value = entries.get(name);
        require(value != null && value.length > 0, "Missing release file: " + name);
        return value;
    }

    private static Element child(Element parent, String name) {
        return children(parent, name).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Missing metadata element: " + name));
    }

    private static List<Element> children(Element parent, String name) {
        var result = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) result.add(element);
        }
        return result;
    }

    private static String text(Element parent, String name) { return child(parent, name).getTextContent().trim().replaceAll("\\s+", " "); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
