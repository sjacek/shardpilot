import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Standalone, JDK-only cleanup of static PlantUML previews; not part of the published plugin. */
public final class NormalizeSvg {
    private static final String SVG = "http://www.w3.org/2000/svg";
    private static final String XLINK = "http://www.w3.org/1999/xlink";
    private static final Set<String> TEXT_ELEMENTS = Set.of("text", "textPath", "tspan");

    private NormalizeSvg() { }

    /** Normalizes explicit files only; source launching also works without a Gradle build. */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0) {
            throw new IllegalArgumentException("Usage: java scripts/NormalizeSvg.java <preview.svg> [...]");
        }
        for (String argument : arguments) {
            Path path = Path.of(argument);
            if (path.getFileName() == null || !path.getFileName().toString().endsWith(".svg")) {
                throw new IllegalArgumentException("Expected an explicit .svg preview path");
            }
            String source = Files.readString(path, StandardCharsets.UTF_8);
            String normalized = normalize(source);
            if (!source.equals(normalized)) Files.writeString(path, normalized, StandardCharsets.UTF_8);
        }
    }

    static String normalize(String source) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(
                new org.xml.sax.InputSource(new StringReader(source)));
        Element root = document.getDocumentElement();
        if (!SVG.equals(root.getNamespaceURI()) || !"svg".equals(root.getLocalName())) {
            throw new IllegalArgumentException("Expected an SVG document");
        }
        normalizeElement(root, null);
        if (!usesNamespace(root, XLINK)) root.removeAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xlink");

        var transformers = TransformerFactory.newInstance();
        transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = transformers.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        var output = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toString().stripTrailing() + "\n";
    }

    private static void normalizeElement(Element element, String groupAdjustment) {
        boolean svgElement = SVG.equals(element.getNamespaceURI());
        String adjustment = groupAdjustment;
        if (svgElement && element.hasAttribute("lengthAdjust")) {
            adjustment = element.getAttribute("lengthAdjust");
            // PlantUML 1.2026.8 places this text-only attribute on its root <g>.
            if (!TEXT_ELEMENTS.contains(element.getLocalName())) element.removeAttribute("lengthAdjust");
        }
        if (svgElement && TEXT_ELEMENTS.contains(element.getLocalName()) && adjustment != null) {
            element.setAttribute("lengthAdjust", adjustment);
        }
        // Static previews do not use PlantUML's interactive/source-mapping data attributes.
        var attributes = element.getAttributes();
        for (int index = attributes.getLength() - 1; index >= 0; index--) {
            Node attribute = attributes.item(index);
            if (attribute.getNamespaceURI() == null && attribute.getNodeName().startsWith("data-")) {
                element.removeAttributeNode((org.w3c.dom.Attr) attribute);
            }
        }
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element nested) normalizeElement(nested, adjustment);
        }
    }

    private static boolean usesNamespace(Element element, String namespace) {
        if (namespace.equals(element.getNamespaceURI())) return true;
        var attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            if (namespace.equals(attributes.item(index).getNamespaceURI())) return true;
        }
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element nested && usesNamespace(nested, namespace)) return true;
        }
        return false;
    }
}
