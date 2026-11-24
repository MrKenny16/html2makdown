import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/**
 * Analizador de documentos HTML basado en {@link ParserDelegator} y
 * {@link HTMLEditorKit.ParserCallback} de la API estándar de Java.
 * Construye un árbol ligero de nodos que preserva la jerarquía de elementos
 * anidados para su posterior conversión a Markdown.
 */
public class HtmlParser {

    private static final Set<String> IGNORED_CONTAINER_TAGS = Set.of("script", "style", "head", "noscript");
    private static final Set<String> VOID_TAGS = Set.of("br", "hr", "img");

    /**
     * Nodo base del árbol HTML simplificado.
     */
    public sealed interface HtmlNode permits ElementNode, TextNode {
    }

    /**
     * Nodo que representa un elemento HTML con etiqueta, atributos e hijos.
     */
    public static final class ElementNode implements HtmlNode {
        private final String tagName;
        private final Map<String, String> attributes;
        private final List<HtmlNode> children;

        public ElementNode(String tagName, Map<String, String> attributes) {
            this.tagName = tagName;
            this.attributes = attributes;
            this.children = new ArrayList<>();
        }

        public String getTagName() {
            return tagName;
        }

        public Map<String, String> getAttributes() {
            return attributes;
        }

        public String getAttribute(String name) {
            return attributes.get(name.toLowerCase(Locale.ROOT));
        }

        public List<HtmlNode> getChildren() {
            return Collections.unmodifiableList(children);
        }

        void addChild(HtmlNode child) {
            children.add(child);
        }
    }

    /**
     * Nodo que representa contenido textual dentro de un elemento HTML.
     */
    public static final class TextNode implements HtmlNode {
        private final String text;

        public TextNode(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    /**
     * Analiza una cadena HTML y devuelve el nodo raíz del documento construido.
     *
     * @param html contenido HTML en formato texto
     * @return nodo raíz {@link ElementNode} con etiqueta {@code document}
     * @throws IOException si ocurre un error de lectura durante el análisis
     */
    public ElementNode analizar(String html) throws IOException {
        ElementNode root = new ElementNode("document", Map.of());
        if (html == null || html.isBlank()) {
            return root;
        }

        TreeBuilderCallback callback = new TreeBuilderCallback(root);
        ParserDelegator parser = new ParserDelegator();
        try (StringReader reader = new StringReader(html)) {
            parser.parse(reader, callback, true);
        }
        return root;
    }

    private static Map<String, String> extraerAtributos(MutableAttributeSet attributeSet) {
        if (attributeSet == null || attributeSet.getAttributeCount() == 0) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        Enumeration<?> names = attributeSet.getAttributeNames();
        while (names.hasMoreElements()) {
            Object key = names.nextElement();
            if (HTMLEditorKit.ParserCallback.IMPLIED.equals(key) || HTML.Attribute.ENDTAG.equals(key)) {
                continue;
            }
            Object value = attributeSet.getAttribute(key);
            if (key != null && value != null) {
                result.put(key.toString().toLowerCase(Locale.ROOT), value.toString());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static final class TreeBuilderCallback extends HTMLEditorKit.ParserCallback {
        private final Deque<ElementNode> elementStack = new ArrayDeque<>();
        private final Deque<String> ignoredTagStack = new ArrayDeque<>();

        TreeBuilderCallback(ElementNode root) {
            elementStack.push(root);
        }

        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
            String tagName = tag.toString().toLowerCase(Locale.ROOT);
            if (!ignoredTagStack.isEmpty()) {
                if (IGNORED_CONTAINER_TAGS.contains(tagName)) {
                    ignoredTagStack.push(tagName);
                }
                return;
            }
            if (IGNORED_CONTAINER_TAGS.contains(tagName)) {
                ignoredTagStack.push(tagName);
                return;
            }

            ElementNode node = new ElementNode(tagName, extraerAtributos(attributes));
            elementStack.peek().addChild(node);
            elementStack.push(node);
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int pos) {
            String tagName = tag.toString().toLowerCase(Locale.ROOT);
            if (!ignoredTagStack.isEmpty()) {
                if (ignoredTagStack.peek().equals(tagName)) {
                    ignoredTagStack.pop();
                } else if (ignoredTagStack.contains(tagName)) {
                    while (!ignoredTagStack.isEmpty() && !ignoredTagStack.peek().equals(tagName)) {
                        ignoredTagStack.pop();
                    }
                    if (!ignoredTagStack.isEmpty()) {
                        ignoredTagStack.pop();
                    }
                }
                return;
            }

            if (elementStack.size() <= 1) {
                return;
            }

            boolean found = false;
            for (ElementNode node : elementStack) {
                if (node.getTagName().equals(tagName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return;
            }

            while (elementStack.size() > 1) {
                ElementNode popped = elementStack.pop();
                if (popped.getTagName().equals(tagName)) {
                    break;
                }
            }
        }

        @Override
        public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
            String tagName = tag.toString().toLowerCase(Locale.ROOT);
            boolean isEndTag = attributes != null
                    && "true".equalsIgnoreCase(String.valueOf(attributes.getAttribute(HTML.Attribute.ENDTAG)));

            if (!ignoredTagStack.isEmpty()) {
                if (isEndTag && ignoredTagStack.peek().equals(tagName)) {
                    ignoredTagStack.pop();
                }
                return;
            }

            if (isEndTag) {
                return;
            }

            if (VOID_TAGS.contains(tagName)) {
                ElementNode leaf = new ElementNode(tagName, extraerAtributos(attributes));
                elementStack.peek().addChild(leaf);
            }
        }

        @Override
        public void handleText(char[] data, int pos) {
            if (!ignoredTagStack.isEmpty() || data == null || data.length == 0) {
                return;
            }
            elementStack.peek().addChild(new TextNode(new String(data)));
        }

        @Override
        public void handleComment(char[] data, int pos) {
            // Los comentarios HTML se omiten de la salida Markdown.
        }

        @Override
        public void handleError(String errorMsg, int pos) {
            // Se ignoran advertencias menores del DTD HTML 3.2 ante etiquetas HTML5 modernas.
        }
    }
}
