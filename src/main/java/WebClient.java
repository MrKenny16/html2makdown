import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente HTTP encargado de recuperar documentos HTML remotos usando
 * exclusivamente la API estándar de Java ({@link URL}, {@link URLConnection}
 * y {@link HttpURLConnection}).
 */
public class WebClient {

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 15_000;
    public static final int DEFAULT_MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_SIZE = 8192;

    private static final Pattern HEADER_CHARSET_PATTERN =
            Pattern.compile("charset\\s*=\\s*\"?([^;\\s\"]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CHARSET_PATTERN =
            Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9._-]+)", Pattern.CASE_INSENSITIVE);

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxBytes;

    private int lastStatusCode;
    private String lastContentType;
    private URL lastFinalUrl;

    /**
     * Crea un cliente con tiempos de espera y límite de tamaño predeterminados.
     */
    public WebClient() {
        this(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, DEFAULT_MAX_BYTES);
    }

    /**
     * Crea un cliente con parámetros personalizados.
     *
     * @param connectTimeoutMs tiempo máximo de conexión en milisegundos
     * @param readTimeoutMs    tiempo máximo de lectura en milisegundos
     * @param maxBytes         tamaño máximo permitido en bytes para el HTML
     */
    public WebClient(int connectTimeoutMs, int readTimeoutMs, int maxBytes) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxBytes = maxBytes;
    }

    /**
     * Descarga el documento HTML de la URL indicada validando protocolo,
     * estado HTTP, tipo de contenido, codificación y tamaño máximo.
     *
     * @param url dirección HTTP o HTTPS a descargar
     * @return contenido HTML decodificado como cadena
     * @throws IOException si ocurre un error de red, protocolo, validación o tamaño
     */
    public String descargarHTML(URL url) throws IOException {
        if (url == null) {
            throw new IllegalArgumentException("La URL no puede ser nula.");
        }
        validarProtocoloHttp(url);

        URL currentUrl = url;
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            URLConnection rawConnection = currentUrl.openConnection();
            if (!(rawConnection instanceof HttpURLConnection connection)) {
                throw new IOException("La conexión no corresponde al protocolo HTTP/HTTPS: " + currentUrl);
            }

            try {
                configurarConexion(connection);
                int status = connection.getResponseCode();
                this.lastStatusCode = status;
                this.lastFinalUrl = currentUrl;

                if (esRedireccion(status)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("Respuesta de redirección (" + status + ") sin cabecera Location.");
                    }
                    if (redirectCount == MAX_REDIRECTS) {
                        throw new IOException("Se superó el límite máximo de redirecciones (" + MAX_REDIRECTS + ").");
                    }
                    currentUrl = resolverRedireccion(currentUrl, location);
                    continue;
                }

                if (status < 200 || status >= 300) {
                    String message = connection.getResponseMessage();
                    throw new IOException("Respuesta HTTP no exitosa: " + status
                            + (message != null && !message.isBlank() ? " " + message : ""));
                }

                String contentType = connection.getContentType();
                this.lastContentType = contentType;
                validarTipoContenidoHtml(contentType);

                long contentLength = connection.getContentLengthLong();
                if (contentLength > maxBytes) {
                    throw new IOException("El documento excede el límite máximo permitido de "
                            + maxBytes + " bytes (Content-Length: " + contentLength + ").");
                }

                byte[] bodyBytes = leerCuerpoLimitado(connection);
                Charset charset = determinarCharset(contentType, bodyBytes);
                return new String(bodyBytes, charset);
            } finally {
                connection.disconnect();
            }
        }

        throw new IOException("No fue posible completar la descarga tras seguir las redirecciones.");
    }

    public int getLastStatusCode() {
        return lastStatusCode;
    }

    public String getLastContentType() {
        return lastContentType;
    }

    public URL getLastFinalUrl() {
        return lastFinalUrl;
    }

    private void configurarConexion(HttpURLConnection connection) throws IOException {
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("User-Agent", "HTML2Markdown/1.0 (Java)");
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1");
        connection.setRequestProperty("Accept-Charset", "UTF-8, ISO-8859-1;q=0.8");
    }

    private static void validarProtocoloHttp(URL url) throws IOException {
        String protocol = url.getProtocol();
        if (protocol == null) {
            throw new IOException("La URL no especifica protocolo.");
        }
        String normalized = protocol.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalized) && !"https".equals(normalized)) {
            throw new IOException("Protocolo no admitido: " + protocol + ". Solo se permite HTTP o HTTPS.");
        }
        if (url.getHost() == null || url.getHost().isBlank()) {
            throw new IOException("La URL no contiene un host válido: " + url);
        }
    }

    private static boolean esRedireccion(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307
                || status == 308;
    }

    private static URL resolverRedireccion(URL baseUrl, String location) throws IOException {
        try {
            URI baseUri = baseUrl.toURI();
            URI resolvedUri = baseUri.resolve(location.trim());
            URL nextUrl = resolvedUri.toURL();
            validarProtocoloHttp(nextUrl);
            return nextUrl;
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("URL de redirección inválida: " + location, e);
        }
    }

    private static void validarTipoContenidoHtml(String contentType) throws IOException {
        if (contentType == null || contentType.isBlank()) {
            throw new IOException("El servidor no indicó el tipo de contenido (Content-Type).");
        }
        String mimeType = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!"text/html".equals(mimeType) && !"application/xhtml+xml".equals(mimeType)) {
            throw new IOException("El recurso no es un documento HTML válido (Content-Type: " + contentType + ").");
        }
    }

    private byte[] leerCuerpoLimitado(HttpURLConnection connection) throws IOException {
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int totalRead = 0;
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                totalRead += bytesRead;
                if (totalRead > maxBytes) {
                    throw new IOException("El documento HTML excede el tamaño máximo permitido de "
                            + maxBytes + " bytes.");
                }
                output.write(buffer, 0, bytesRead);
            }
            return output.toByteArray();
        }
    }

    private static Charset determinarCharset(String contentType, byte[] bodyBytes) {
        Charset headerCharset = extraerCharsetDeCabecera(contentType);
        if (headerCharset != null) {
            return headerCharset;
        }

        int previewLength = Math.min(bodyBytes.length, 4096);
        String preview = new String(bodyBytes, 0, previewLength, StandardCharsets.ISO_8859_1);
        Matcher metaMatcher = META_CHARSET_PATTERN.matcher(preview);
        if (metaMatcher.find()) {
            Charset metaCharset = resolverCharsetSeguro(metaMatcher.group(1));
            if (metaCharset != null) {
                return metaCharset;
            }
        }

        return StandardCharsets.UTF_8;
    }

    private static Charset extraerCharsetDeCabecera(String contentType) {
        if (contentType == null) {
            return null;
        }
        Matcher matcher = HEADER_CHARSET_PATTERN.matcher(contentType);
        if (matcher.find()) {
            return resolverCharsetSeguro(matcher.group(1));
        }
        return null;
    }

    private static Charset resolverCharsetSeguro(String charsetName) {
        if (charsetName == null || charsetName.isBlank()) {
            return null;
        }
        try {
            String cleaned = charsetName.trim();
            if (Charset.isSupported(cleaned)) {
                return Charset.forName(cleaned);
            }
        } catch (IllegalArgumentException ignored) {
            // Se usa UTF-8 como respaldo si el nombre del charset es inválido.
        }
        return null;
    }
}
