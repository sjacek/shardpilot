import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NormalizeSvgTest {
    private static final String SVG = "http://www.w3.org/2000/svg";
    private static final String XLINK = "http://www.w3.org/1999/xlink";

    @Test
    void movesGroupAdjustmentToTextAndPreservesExplicitOverrides() throws Exception {
        Document result = parse(NormalizeSvg.normalize("""
                <svg xmlns="http://www.w3.org/2000/svg"><g lengthAdjust="spacingAndGlyphs">
                  <text textLength="40">Alpha</text>
                  <g lengthAdjust="spacing"><text>Beta</text></g>
                  <text lengthAdjust="spacing">Gamma</text>
                </g></svg>
                """));
        var texts = result.getElementsByTagNameNS(SVG, "text");
        assertEquals("spacingAndGlyphs", ((Element) texts.item(0)).getAttribute("lengthAdjust"));
        assertEquals("40", ((Element) texts.item(0)).getAttribute("textLength"));
        assertEquals("spacing", ((Element) texts.item(1)).getAttribute("lengthAdjust"));
        assertEquals("spacing", ((Element) texts.item(2)).getAttribute("lengthAdjust"));
        var groups = result.getElementsByTagNameNS(SVG, "g");
        for (int index = 0; index < groups.getLength(); index++) {
            assertFalse(((Element) groups.item(index)).hasAttribute("lengthAdjust"));
        }
    }

    @Test
    void stripsOnlyUnqualifiedMetadataAndRetainsGeometryAndLabels() throws Exception {
        Document result = parse(NormalizeSvg.normalize("""
                <svg xmlns="http://www.w3.org/2000/svg" xmlns:custom="urn:example" viewBox="0 0 100 50" data-diagram-type="DESCRIPTION">
                  <g id="node" data-source-line="1" data-qualified-name="A" custom:data-label="retained">
                    <path d="M1,2 L3,4" fill="#123456"/>
                    <text>Alpha &amp; Żółć</text>
                  </g>
                </svg>
                """));
        Element group = (Element) result.getElementsByTagNameNS(SVG, "g").item(0);
        assertFalse(result.getDocumentElement().hasAttribute("data-diagram-type"));
        assertFalse(group.hasAttribute("data-source-line"));
        assertFalse(group.hasAttribute("data-qualified-name"));
        assertEquals("retained", group.getAttributeNS("urn:example", "data-label"));
        assertEquals("node", group.getAttribute("id"));
        assertEquals("0 0 100 50", result.getDocumentElement().getAttribute("viewBox"));
        Element path = (Element) result.getElementsByTagNameNS(SVG, "path").item(0);
        assertEquals("M1,2 L3,4", path.getAttribute("d"));
        assertEquals("#123456", path.getAttribute("fill"));
        assertEquals("Alpha & Żółć", result.getElementsByTagNameNS(SVG, "text").item(0).getTextContent());
    }

    @Test
    void removesUnusedXlinkButPreservesRealLinks() throws Exception {
        String empty = "<svg xmlns=\"" + SVG + "\" xmlns:xlink=\"" + XLINK + "\"/>";
        assertFalse(parse(NormalizeSvg.normalize(empty)).getDocumentElement()
                .hasAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xlink"));
        String linked = empty.replace("/>", "><a xlink:href=\"https://example.org\"><text>Docs</text></a></svg>");
        Document result = parse(NormalizeSvg.normalize(linked));
        assertEquals(XLINK, result.getDocumentElement().getAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xlink"));
        assertEquals("https://example.org", ((Element) result.getElementsByTagNameNS(SVG, "a").item(0))
                .getAttributeNS(XLINK, "href"));
    }

    @Test
    void rejectsDoctypeAndNonSvgDocuments() {
        assertThrows(SAXParseException.class, () -> NormalizeSvg.normalize("""
                <!DOCTYPE svg [<!ENTITY external SYSTEM "file:///must-not-be-read">]>
                <svg xmlns="http://www.w3.org/2000/svg"><text>&external;</text></svg>
                """));
        assertThrows(IllegalArgumentException.class, () -> NormalizeSvg.normalize("<svg/>"));
        assertThrows(IllegalArgumentException.class, () -> NormalizeSvg.normalize("<html/>"));
    }

    @Test
    void fileNormalizationIsIdempotent(@TempDir Path directory) throws Exception {
        Path preview = directory.resolve("preview.svg");
        Files.writeString(preview, "<svg xmlns=\"" + SVG + "\"><g lengthAdjust=\"spacing\"><text>One</text></g></svg>");
        NormalizeSvg.main(new String[]{preview.toString()});
        String first = Files.readString(preview);
        NormalizeSvg.main(new String[]{preview.toString()});
        assertEquals(first, Files.readString(preview));
        assertThrows(IllegalArgumentException.class, () -> NormalizeSvg.main(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> NormalizeSvg.main(new String[]{"source.puml"}));
    }

    @Test
    void committedPreviewsContainOnlyTextLengthAdjustmentsAndNoInteractiveMetadata() throws Exception {
        Path directory = Path.of("docs", "diagrams");
        try (var sources = Files.list(directory)) {
            var diagrams = sources.filter(path -> path.toString().endsWith(".puml")).toList();
            assertFalse(diagrams.isEmpty(), "At least one diagram must be checked");
            for (Path source : diagrams) {
                Path preview = source.resolveSibling(source.getFileName().toString().replace(".puml", ".svg"));
                Document document = parse(Files.readString(preview));
                var elements = document.getElementsByTagNameNS(SVG, "*");
                for (int index = 0; index < elements.getLength(); index++) {
                    Element element = (Element) elements.item(index);
                    if (element.hasAttribute("lengthAdjust")) {
                        assertTrue(Set.of("text", "textPath", "tspan").contains(element.getLocalName()), preview.toString());
                    }
                    var attributes = element.getAttributes();
                    for (int attribute = 0; attribute < attributes.getLength(); attribute++) {
                        assertFalse(attributes.item(attribute).getNodeName().startsWith("data-"), preview.toString());
                    }
                }
                assertFalse(document.getDocumentElement().hasAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xlink"),
                        "These static previews have no links: " + preview);
            }
        }
    }

    private static Document parse(String svg) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
    }
}
