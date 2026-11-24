import java.net.URI;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte el árbol de nodos HTML generado por {@link HtmlParser} en sintaxis
 * Markdown preservando estructura semántica, anidamiento, listas, espacios y
 * referencias relativas resueltas contra la URL base.
 */
public class MarkdownConverter {

    private static final Pattern WHITESPACE_COLLAPSE = Pattern.compile("[\\s\\u00A0]+");
    private static final Pattern MULTIPLE_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern LANGUAGE_CLASS_PATTERN =
            Pattern.compile("(?:^|\\s)(?:language-|lang-)([A-Za-z0-9_+#.-]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Convierte un documento HTML analizado a Markdown sin resolución de URL relativas.
     *
     * @param root nodo raíz del documento HTML
     * @return texto en formato Markdown
     */
    public String convertir(HtmlParser.ElementNode root) {
        return convertir(root, null);
    }

    /**
     * Convierte un documento HTML analizado a Markdown resolviendo enlaces e imágenes
     * relativos respecto a {@code baseUrl}.
     *
     * @param root    nodo raíz del documento HTML
     * @param baseUrl URL base del documento descargado (puede ser {@code null})
     * @return texto en formato Markdown
     */
    public String convertir(HtmlParser.ElementNode root, URL baseUrl) {
        if (root == null || root.getChildren().isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        Deque<ListState> listStack = new ArrayDeque<>();
        renderChildren(root.getChildren(), out, baseUrl, listStack);

        String normalized = normalizarSalidaFinal(out.toString());
        return normalized.isEmpty() ? "" : normalized + "\n";
    }

    private void renderChildren(List<HtmlParser.HtmlNode> children,
                                StringBuilder out,
                                URL baseUrl,
                                Deque<ListState> listStack) {
        for (HtmlParser.HtmlNode child : children) {
            renderNode(child, out, baseUrl, listStack);
        }
    }

    private void renderNode(HtmlParser.HtmlNode node,
                            StringBuilder out,
                            URL baseUrl,
                            Deque<ListState> listStack) {
        if (node instanceof HtmlParser.TextNode textNode) {
            String collapsed = colapsarEspacios(textNode.getText());
            if (!collapsed.isEmpty()) {
                appendNormalizedText(out, escaparTextoMarkdown(collapsed));
            }
            return;
        }

        if (!(node instanceof HtmlParser.ElementNode element)) {
            return;
        }

        String tag = element.getTagName();
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = tag.charAt(1) - '0';
                String content = renderInline(element.getChildren(), baseUrl).trim();
                if (!content.isEmpty()) {
                    ensureBlockBreak(out);
                    out.append("#".repeat(level)).append(' ').append(content);
                    ensureBlockBreak(out);
                }
            }
            case "p" -> {
                String content = renderInline(element.getChildren(), baseUrl).trim();
                if (!content.isEmpty()) {
                    ensureBlockBreak(out);
                    out.append(content);
                    ensureBlockBreak(out);
                }
            }
            case "br" -> {
                trimTrailingSpacesBeforeBreak(out);
                out.append("  \n");
            }
            case "hr" -> {
                ensureBlockBreak(out);
                out.append("---");
                ensureBlockBreak(out);
            }
            case "strong", "b" -> {
                String content = renderInline(element.getChildren(), baseUrl);
                appendWrappedInline(out, content, "**");
            }
            case "em", "i" -> {
                String content = renderInline(element.getChildren(), baseUrl);
                appendWrappedInline(out, content, "*");
            }
            case "code" -> {
                String code = extractCodeText(element);
                appendInlineCode(out, code);
            }
            case "pre" -> {
                String code = extractPreText(element);
                String stripped = trimOuterBlankLines(code);
                if (!stripped.isEmpty()) {
                    String fence = elegirDelimitadorCodigo(stripped);
                    String language = detectarLenguajeCodigo(element);
                    ensureBlockBreak(out);
                    out.append(fence).append(language).append('\n')
                       .append(stripped).append('\n')
                       .append(fence);
                    ensureBlockBreak(out);
                }
            }
            case "blockquote" -> {
                StringBuilder inner = new StringBuilder();
                renderChildren(element.getChildren(), inner, baseUrl, new ArrayDeque<>());
                String body = normalizarSalidaFinal(inner.toString());
                if (!body.isEmpty()) {
                    ensureBlockBreak(out);
                    String[] lines = body.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (i > 0) {
                            out.append('\n');
                        }
                        if (lines[i].isEmpty()) {
                            out.append('>');
                        } else {
                            out.append("> ").append(lines[i]);
                        }
                    }
                    ensureBlockBreak(out);
                }
            }
            case "ul" -> renderList(element, false, out, baseUrl, listStack);
            case "ol" -> renderList(element, true, out, baseUrl, listStack);
            case "li" -> {
                // Elemento <li> fuera de <ul>/<ol>: se trata como viñeta simple.
                String content = renderInline(element.getChildren(), baseUrl).trim();
                if (!content.isEmpty()) {
                    ensureLineBreak(out);
                    out.append("- ").append(content).append('\n');
                }
            }
            case "a" -> renderLink(element, out, baseUrl);
            case "img" -> renderImage(element, out, baseUrl);
            default -> renderChildren(element.getChildren(), out, baseUrl, listStack);
        }
    }

    private String renderInline(List<HtmlParser.HtmlNode> children, URL baseUrl) {
        StringBuilder sb = new StringBuilder();
        Deque<ListState> emptyListStack = new ArrayDeque<>();
        for (HtmlParser.HtmlNode child : children) {
            renderNode(child, sb, baseUrl, emptyListStack);
        }
        return sb.toString();
    }

    private void renderList(HtmlParser.ElementNode listElement,
                            boolean ordered,
                            StringBuilder out,
                            URL baseUrl,
                            Deque<ListState> listStack) {
        int depth = listStack.size();
        if (depth == 0) {
            ensureBlockBreak(out);
        } else {
            ensureLineBreak(out);
        }

        ListState state = new ListState(ordered);
        listStack.push(state);
        try {
            for (HtmlParser.HtmlNode child : listElement.getChildren()) {
                if (child instanceof HtmlParser.ElementNode childElem) {
                    if ("li".equals(childElem.getTagName())) {
                        renderListItem(childElem, state, depth, out, baseUrl, listStack);
                    } else if ("ul".equals(childElem.getTagName())) {
                        renderList(childElem, false, out, baseUrl, listStack);
                    } else if ("ol".equals(childElem.getTagName())) {
                        renderList(childElem, true, out, baseUrl, listStack);
                    }
                }
            }
        } finally {
            listStack.pop();
        }

        if (depth == 0) {
            ensureBlockBreak(out);
        }
    }

    private void renderListItem(HtmlParser.ElementNode liElement,
                                ListState state,
                                int depth,
                                StringBuilder out,
                                URL baseUrl,
                                Deque<ListState> listStack) {
        String indent = "    ".repeat(depth);
        String marker = state.ordered ? (state.nextIndex++ + ". ") : "- ";

        List<HtmlParser.HtmlNode> inlineNodes = new java.util.ArrayList<>();
        List<HtmlParser.ElementNode> nestedLists = new java.util.ArrayList<>();
        List<HtmlParser.HtmlNode> extraBlocks = new java.util.ArrayList<>();

        for (HtmlParser.HtmlNode child : liElement.getChildren()) {
            if (child instanceof HtmlParser.ElementNode elem) {
                String tag = elem.getTagName();
                if ("ul".equals(tag) || "ol".equals(tag)) {
                    nestedLists.add(elem);
                } else if (inlineNodes.isEmpty() && nestedLists.isEmpty() && "p".equals(tag)) {
                    inlineNodes.addAll(elem.getChildren());
                } else if ("p".equals(tag) || "pre".equals(tag) || "blockquote".equals(tag)) {
                    extraBlocks.add(elem);
                } else {
                    inlineNodes.add(elem);
                }
            } else {
                inlineNodes.add(child);
            }
        }

        String primaryText = renderInline(inlineNodes, baseUrl).trim();
        ensureLineBreak(out);
        out.append(indent).append(marker);
        if (!primaryText.isEmpty()) {
            String continuationIndent = indent + " ".repeat(marker.length());
            String[] lines = primaryText.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    out.append('\n').append(continuationIndent);
                }
                out.append(lines[i].stripTrailing());
            }
        }
        out.append('\n');

        for (HtmlParser.HtmlNode block : extraBlocks) {
            StringBuilder blockOut = new StringBuilder();
            renderNode(block, blockOut, baseUrl, listStack);
            String renderedBlock = normalizarSalidaFinal(blockOut.toString());
            if (!renderedBlock.isEmpty()) {
                String continuationIndent = indent + " ".repeat(marker.length());
                out.append('\n');
                for (String line : renderedBlock.split("\n", -1)) {
                    if (line.isEmpty()) {
                        out.append('\n');
                    } else {
                        out.append(continuationIndent).append(line).append('\n');
                    }
                }
            }
        }

        for (HtmlParser.ElementNode nestedList : nestedLists) {
            renderList(nestedList, "ol".equals(nestedList.getTagName()), out, baseUrl, listStack);
        }
    }

    private void renderLink(HtmlParser.ElementNode element, StringBuilder out, URL baseUrl) {
        String href = element.getAttribute("href");
        String rawInner = renderInline(element.getChildren(), baseUrl);
        String trimmedInner = rawInner.trim();

        if (href == null || href.isBlank() || href.trim().toLowerCase(Locale.ROOT).startsWith("javascript:")) {
            appendNormalizedText(out, rawInner);
            return;
        }

        String resolvedUrl = resolverUrl(href.trim(), baseUrl);
        String safeUrl = resolvedUrl.replace(")", "%29").replace(" ", "%20");
        String linkText = trimmedInner.isEmpty() ? safeUrl : trimmedInner;

        boolean hasLeadingSpace = !rawInner.isEmpty() && Character.isWhitespace(rawInner.charAt(0));
        boolean hasTrailingSpace = !rawInner.isEmpty() && Character.isWhitespace(rawInner.charAt(rawInner.length() - 1));

        if (hasLeadingSpace) {
            appendSpaceIfNeeded(out);
        }
        out.append('[').append(linkText).append("](").append(safeUrl).append(')');
        if (hasTrailingSpace) {
            out.append(' ');
        }
    }

    private void renderImage(HtmlParser.ElementNode element, StringBuilder out, URL baseUrl) {
        String src = element.getAttribute("src");
        if (src == null || src.isBlank() || src.trim().toLowerCase(Locale.ROOT).startsWith("javascript:")) {
            return;
        }
        String resolvedUrl = resolverUrl(src.trim(), baseUrl);
        String safeUrl = resolvedUrl.replace(")", "%29").replace(" ", "%20");
        String alt = element.getAttribute("alt");
        String safeAlt = alt == null ? "" : alt.trim().replace("[", "\\[").replace("]", "\\]");
        out.append("![").append(safeAlt).append("](").append(safeUrl).append(')');
    }

    private static String resolverUrl(String reference, URL baseUrl) {
        if (reference.startsWith("#")) {
            return reference;
        }
        String lower = reference.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("data:")) {
            return reference;
        }
        if (baseUrl == null) {
            return reference;
        }
        try {
            URI baseUri = baseUrl.toURI();
            return baseUri.resolve(reference).toString();
        } catch (Exception e) {
            try {
                return new URL(baseUrl, reference).toString();
            } catch (Exception ignored) {
                return reference;
            }
        }
    }

    private static void appendWrappedInline(StringBuilder out, String rawContent, String delimiter) {
        String trimmed = rawContent.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        boolean hasLeadingSpace = Character.isWhitespace(rawContent.charAt(0));
        boolean hasTrailingSpace = Character.isWhitespace(rawContent.charAt(rawContent.length() - 1));

        if (hasLeadingSpace) {
            appendSpaceIfNeeded(out);
        }
        out.append(delimiter).append(trimmed).append(delimiter);
        if (hasTrailingSpace) {
            out.append(' ');
        }
    }

    private static void appendInlineCode(StringBuilder out, String rawCode) {
        String collapsed = colapsarEspacios(rawCode).trim();
        if (collapsed.isEmpty()) {
            return;
        }
        int maxRun = maxConsecutiveBackticks(collapsed);
        String fence = "`".repeat(maxRun + 1);
        boolean pad = collapsed.startsWith("`") || collapsed.endsWith("`");
        out.append(fence);
        if (pad) {
            out.append(' ');
        }
        out.append(collapsed);
        if (pad) {
            out.append(' ');
        }
        out.append(fence);
    }

    private static String extractCodeText(HtmlParser.ElementNode element) {
        StringBuilder sb = new StringBuilder();
        for (HtmlParser.HtmlNode child : element.getChildren()) {
            if (child instanceof HtmlParser.TextNode textNode) {
                sb.append(textNode.getText());
            } else if (child instanceof HtmlParser.ElementNode childElem) {
                if ("br".equals(childElem.getTagName())) {
                    sb.append(' ');
                } else {
                    sb.append(extractCodeText(childElem));
                }
            }
        }
        return sb.toString();
    }

    private static String extractPreText(HtmlParser.ElementNode element) {
        StringBuilder sb = new StringBuilder();
        for (HtmlParser.HtmlNode child : element.getChildren()) {
            if (child instanceof HtmlParser.TextNode textNode) {
                sb.append(textNode.getText().replace("\r\n", "\n").replace('\r', '\n'));
            } else if (child instanceof HtmlParser.ElementNode childElem) {
                if ("br".equals(childElem.getTagName())) {
                    sb.append('\n');
                } else {
                    sb.append(extractPreText(childElem));
                }
            }
        }
        return sb.toString();
    }

    private static String detectarLenguajeCodigo(HtmlParser.ElementNode preElement) {
        String fromPre = buscarLenguajeEnClase(preElement.getAttribute("class"));
        if (!fromPre.isEmpty()) {
            return fromPre;
        }
        for (HtmlParser.HtmlNode child : preElement.getChildren()) {
            if (child instanceof HtmlParser.ElementNode childElem && "code".equals(childElem.getTagName())) {
                String fromCode = buscarLenguajeEnClase(childElem.getAttribute("class"));
                if (!fromCode.isEmpty()) {
                    return fromCode;
                }
            }
        }
        return "";
    }

    private static String buscarLenguajeEnClase(String classAttr) {
        if (classAttr == null || classAttr.isBlank()) {
            return "";
        }
        Matcher matcher = LANGUAGE_CLASS_PATTERN.matcher(classAttr);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private static String elegirDelimitadorCodigo(String code) {
        int maxRun = maxConsecutiveBackticks(code);
        return "`".repeat(Math.max(3, maxRun + 1));
    }

    private static int maxConsecutiveBackticks(String text) {
        int max = 0;
        int current = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '`') {
                current++;
                if (current > max) {
                    max = current;
                }
            } else {
                current = 0;
            }
        }
        return max;
    }

    private static String colapsarEspacios(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return WHITESPACE_COLLAPSE.matcher(text).replaceAll(" ");
    }

    private static String escaparTextoMarkdown(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\', '*', '_', '`', '[', ']', '<', '>' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void appendNormalizedText(StringBuilder out, String text) {
        if (text.isEmpty()) {
            return;
        }
        int start = 0;
        if (" ".equals(text)) {
            appendSpaceIfNeeded(out);
            return;
        }
        if (text.charAt(0) == ' ') {
            appendSpaceIfNeeded(out);
            start = 1;
        }
        if (start < text.length()) {
            out.append(text, start, text.length());
        }
    }

    private static void appendSpaceIfNeeded(StringBuilder out) {
        if (out.isEmpty()) {
            return;
        }
        char last = out.charAt(out.length() - 1);
        if (last != ' ' && last != '\n' && last != '\t') {
            out.append(' ');
        }
    }

    private static void trimTrailingSpacesBeforeBreak(StringBuilder out) {
        while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
            out.deleteCharAt(out.length() - 1);
        }
    }

    private static void ensureLineBreak(StringBuilder out) {
        if (out.isEmpty()) {
            return;
        }
        trimTrailingSpacesBeforeBreak(out);
        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private static void ensureBlockBreak(StringBuilder out) {
        if (out.isEmpty()) {
            return;
        }
        while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
            out.deleteCharAt(out.length() - 1);
        }
        int trailingNewlines = 0;
        for (int i = out.length() - 1; i >= 0 && out.charAt(i) == '\n'; i--) {
            trailingNewlines++;
        }
        while (trailingNewlines < 2) {
            out.append('\n');
            trailingNewlines++;
        }
    }

    private static String trimOuterBlankLines(String text) {
        String[] lines = text.split("\n", -1);
        int start = 0;
        int end = lines.length - 1;
        while (start <= end && lines[start].isBlank()) {
            start++;
        }
        while (end >= start && lines[end].isBlank()) {
            end--;
        }
        if (start > end) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i > start) {
                sb.append('\n');
            }
            sb.append(lines[i].stripTrailing());
        }
        return sb.toString();
    }

    private static String normalizarSalidaFinal(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String[] lines = raw.split("\n", -1);
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i];
            // Conserva dos espacios finales si representan un salto de línea Markdown explícito (<br>).
            if (line.endsWith("  ") && !line.isBlank()) {
                sb.append(line.stripTrailing()).append("  ");
            } else {
                sb.append(line.stripTrailing());
            }
        }
        String collapsed = MULTIPLE_BLANK_LINES.matcher(sb.toString()).replaceAll("\n\n");
        return trimOuterBlankLines(collapsed);
    }

    private static final class ListState {
        final boolean ordered;
        int nextIndex = 1;

        ListState(boolean ordered) {
            this.ordered = ordered;
        }
    }
}
