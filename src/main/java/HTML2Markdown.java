import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Punto de entrada de la aplicación de línea de comandos {@code HTML2Markdown}.
 * Coordina la validación de argumentos, descarga HTTP, análisis de estructura HTML,
 * conversión a Markdown y escritura del archivo resultante en UTF-8.
 */
public class HTML2Markdown {

    private final WebClient webClient;
    private final HtmlParser htmlParser;
    private final MarkdownConverter markdownConverter;
    private final MarkdownWriter markdownWriter;

    public HTML2Markdown() {
        this(new WebClient(), new HtmlParser(), new MarkdownConverter(), new MarkdownWriter());
    }

    public HTML2Markdown(WebClient webClient,
                         HtmlParser htmlParser,
                         MarkdownConverter markdownConverter,
                         MarkdownWriter markdownWriter) {
        this.webClient = webClient;
        this.htmlParser = htmlParser;
        this.markdownConverter = markdownConverter;
        this.markdownWriter = markdownWriter;
    }

    public static void main(String[] args) {
        if (args == null || args.length == 0 || args.length > 2) {
            imprimirUso();
            System.exit(1);
            return;
        }

        URL url;
        try {
            url = validarYConstruirUrl(args[0]);
        } catch (IllegalArgumentException e) {
            System.err.println("Error en la URL proporcionada: " + e.getMessage());
            imprimirUso();
            System.exit(1);
            return;
        }

        Path destino;
        try {
            destino = args.length == 2
                    ? Path.of(args[1])
                    : MarkdownWriter.generarRutaPredeterminada(url);
        } catch (IllegalArgumentException e) {
            System.err.println("Error en la ruta de salida: " + e.getMessage());
            System.exit(1);
            return;
        }

        HTML2Markdown app = new HTML2Markdown();
        try {
            app.ejecutar(url, destino);
        } catch (IOException e) {
            System.err.println("Error durante el proceso: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error inesperado: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Ejecuta el flujo completo de descarga, análisis, conversión y guardado.
     *
     * @param url     URL HTTP o HTTPS del documento HTML
     * @param destino ruta donde se guardará el archivo Markdown
     * @throws IOException si falla la descarga, lectura, análisis o escritura
     */
    public void ejecutar(URL url, Path destino) throws IOException {
        System.out.println("HTML2Markdown");
        System.out.println("--------------------------------");
        System.out.println("URL: " + url);
        System.out.println();

        System.out.println("Conectando con el servidor...");
        String html = webClient.descargarHTML(url);
        System.out.println("Respuesta HTTP: " + webClient.getLastStatusCode());

        System.out.println("Analizando HTML...");
        HtmlParser.ElementNode root = htmlParser.analizar(html);

        System.out.println("Convirtiendo a Markdown...");
        URL baseUrl = webClient.getLastFinalUrl() != null ? webClient.getLastFinalUrl() : url;
        String markdown = markdownConverter.convertir(root, baseUrl);

        markdownWriter.guardar(destino, markdown);
        long sizeBytes = Files.size(destino);

        System.out.println();
        if (markdown.isEmpty()) {
            System.out.println("Aviso: El documento HTML no contiene elementos convertibles; se generó un archivo vacío.");
        }
        System.out.println("Archivo generado: " + destino);
        System.out.printf(Locale.US, "Tamaño: %,d bytes%n", sizeBytes);
        System.out.println();
        System.out.println("Conversión finalizada.");
    }

    private static URL validarYConstruirUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("La URL no puede estar vacía.");
        }
        try {
            URI uri = new URI(rawUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null) {
                throw new IllegalArgumentException("Falta el protocolo (use http:// o https://).");
            }
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
                throw new IllegalArgumentException("Protocolo no válido '" + scheme + "'. Solo se admite http o https.");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("La URL debe incluir un nombre de host válido.");
            }
            return uri.toURL();
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static void imprimirUso() {
        System.out.println("Uso:");
        System.out.println("  java HTML2Markdown <url> [archivo_salida.md]");
        System.out.println();
        System.out.println("Ejemplos:");
        System.out.println("  java HTML2Markdown https://ejemplo.org/articulo.html");
        System.out.println("  java HTML2Markdown https://ejemplo.org/articulo.html salida.md");
    }
}
