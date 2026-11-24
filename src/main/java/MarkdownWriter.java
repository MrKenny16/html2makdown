import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Componente encargado de generar el nombre de archivo predeterminado cuando no
 * se indica una ruta de salida y escribir el contenido Markdown en UTF-8
 * mediante {@link Files}, {@link Path} y {@link StandardCharsets}.
 */
public class MarkdownWriter {

    /**
     * Guarda el contenido Markdown en la ruta indicada usando codificación UTF-8,
     * creando los directorios padres si no existen.
     *
     * @param destino   ruta del archivo Markdown a crear o sobrescribir
     * @param contenido texto Markdown a escribir
     * @throws IOException si ocurre un error al crear directorios o escribir el archivo
     */
    public void guardar(Path destino, String contenido) throws IOException {
        if (destino == null) {
            throw new IllegalArgumentException("La ruta de destino no puede ser nula.");
        }
        Path normalized = destino.normalize();
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String textToWrite = contenido == null ? "" : contenido;
        Files.writeString(normalized, textToWrite, StandardCharsets.UTF_8);
    }

    /**
     * Genera una ruta de salida {@code .md} predeterminada a partir de la URL de origen.
     * Por ejemplo, {@code https://ejemplo.org/articulo.html} produce {@code articulo.md}.
     *
     * @param url URL del documento HTML
     * @return ruta relativa predeterminada en el directorio actual
     */
    public static Path generarRutaPredeterminada(URL url) {
        if (url == null) {
            return Path.of("salida.md");
        }
        String path = url.getPath();
        String fileName = "index";
        if (path != null && !path.isBlank() && !path.endsWith("/")) {
            int lastSlash = path.lastIndexOf('/');
            String segment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            if (!segment.isBlank()) {
                fileName = segment;
            }
        }

        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) {
            fileName = fileName.substring(0, fileName.length() - 5);
        } else if (lower.endsWith(".htm")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }

        fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (fileName.isEmpty()) {
            fileName = "index";
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".md")) {
            fileName += ".md";
        }
        return Path.of(fileName);
    }
}
