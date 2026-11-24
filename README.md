# HTML2Markdown

Aplicación de línea de comandos desarrollada en **Java (17 o superior)** utilizando **exclusivamente clases de la API estándar de Java**, que descarga un documento HTML remoto mediante HTTP/HTTPS, analiza su estructura y lo traduce a un archivo **Markdown** local codificado en **UTF-8**.

---

## Arquitectura y estructura del proyecto

```text
html2markdown/
├── README.md
├── .gitignore
└── src/
    └── main/
        └── java/
            ├── HTML2Markdown.java       # Validación de argumentos, coordinación y resumen
            ├── WebClient.java           # Cliente HTTP con URL, URLConnection y HttpURLConnection
            ├── HtmlParser.java          # Análisis HTML con ParserDelegator y ParserCallback
            ├── MarkdownConverter.java   # Reglas de transformación a Markdown y URL relativas
            └── MarkdownWriter.java      # Escritura de archivos en UTF-8 con Files y Path
```

### Responsabilidades por componente

- **`HTML2Markdown`**: Punto de entrada (`main`). Valida la URL y la ruta de salida opcional, coordina la ejecución paso a paso, muestra el resumen de la operación y controla errores.
- **`WebClient`**: Establece conexiones HTTP/HTTPS con `URL`, `URLConnection` y `HttpURLConnection`, configura tiempos máximos de conexión y lectura, valida códigos de estado 2xx, verifica `Content-Type` HTML, detecta codificación de caracteres (cabecera HTTP, `<meta charset>` o UTF-8 por defecto), limita el tamaño máximo descargado y cierra flujos/conexiones.
- **`HtmlParser`**: Utiliza `javax.swing.text.html.parser.ParserDelegator` y `HTMLEditorKit.ParserCallback` para construir un árbol ligero de nodos (`ElementNode` y `TextNode`), excluyendo `<script>`, `<style>`, `<head>` y comentarios HTML.
- **`MarkdownConverter`**: Recorre el árbol de nodos para generar Markdown válido preservando anidamientos, listas ordenadas y no ordenadas, espacios, escape de caracteres especiales fuera de código y resolución de URL relativas respecto a la URL base.
- **`MarkdownWriter`**: Genera el nombre de archivo `.md` predeterminado a partir de la URL cuando no se especifica uno, crea los directorios destino necesarios y guarda el resultado en UTF-8 mediante `java.nio.file.Files` y `java.nio.file.Path`.

---

## Reglas de conversión admitidas

| Elemento HTML | Conversión Markdown |
| :--- | :--- |
| `<h1>` a `<h6>` | `#` a `######` |
| `<p>` | Párrafo separado por una línea en blanco |
| `<strong>`, `<b>` | `**texto**` |
| `<em>`, `<i>` | `*texto*` |
| `<a href="...">` | `[texto](URL)` (con resolución de URL relativas) |
| `<img src="..." alt="...">` | `![alt](URL)` (con resolución de URL relativas) |
| `<ul>` y `<li>` | Lista con viñetas `-` (con soporte de anidamiento) |
| `<ol>` y `<li>` | Lista numerada `1.`, `2.`, ... (con soporte de anidamiento) |
| `<br>` | Salto de línea Markdown (`  \n`) |
| `<blockquote>` | Cita con prefijo `> ` |
| `<code>` | Código en línea entre acentos graves (`` `código` ``) |
| `<pre>` | Bloque de código delimitado por ```` ``` ```` |
| `<hr>` | Separador horizontal `---` |
| `<script>`, `<style>` | Contenido omitido |

---

## Compilación y ejecución

### Requisitos
- **JDK 17** o superior (sin dependencias externas).

### Desde la terminal

Compilar las clases fuente en la carpeta `out`:

```bash
javac -encoding UTF-8 -d out src/main/java/*.java
```

Ejecutar especificando únicamente la URL (genera el archivo `.md` automáticamente a partir del nombre del recurso, por ejemplo `articulo.md`):

```bash
java -cp out HTML2Markdown https://ejemplo.org/articulo.html
```

Ejecutar especificando una ruta de salida personalizada:

```bash
java -cp out HTML2Markdown https://ejemplo.org/articulo.html output/salida.md
```

### Desde IntelliJ IDEA

1. Abrir la carpeta del proyecto en IntelliJ IDEA.
2. Verificar que el SDK del proyecto sea **Java 17** o superior.
3. Marcar `src/main/java` como **Sources Root**.
4. Ejecutar la clase `HTML2Markdown` pasando la URL (y opcionalmente la ruta de salida) en **Program arguments** dentro de la configuración de ejecución.

---

## Preguntas de reflexión

### 1. ¿Qué papel desempeña la aplicación dentro de una arquitectura cliente-servidor?
La aplicación actúa como **cliente HTTP especializado**. Inicia la comunicación enviando una petición `GET` hacia el servidor web identificado por la URL, recibe la respuesta con el código de estado, cabeceras y cuerpo HTML, y procesa localmente ese recurso para transformarlo en un archivo Markdown. No expone servicios ni atiende peticiones entrantes.

### 2. ¿Qué responsabilidades corresponden al servidor y cuáles al cliente?
- **Servidor**: Alojar o generar el recurso web, atender la petición HTTP, validar rutas y permisos, devolver el código de estado adecuado (por ejemplo, `200`, `301`, `404`), indicar metadatos en las cabeceras (`Content-Type`, `Content-Length`, codificación) y enviar el flujo de bytes del documento HTML.
- **Cliente (`HTML2Markdown`)**: Validar la URL de entrada, configurar tiempos máximos de espera y límites de tamaño, interpretar las cabeceras y el código HTTP, decodificar el cuerpo según el charset, analizar la estructura HTML, aplicar las reglas de conversión a Markdown, resolver URL relativas y persistir el archivo resultante en el sistema de archivos local.

### 3. ¿Qué diferencia existe entre recuperar HTML e interpretar su estructura?
- **Recuperar HTML** es una operación de transporte y decodificación: consiste en establecer la conexión de red, leer los bytes de la respuesta HTTP y convertirlos en una cadena de texto respetando la codificación de caracteres. En este punto, el programa solo tiene texto plano sin significado estructural.
- **Interpretar su estructura** es una operación sintáctica y semántica: consiste en tokenizar y analizar las etiquetas de apertura, cierre, etiquetas vacías, atributos y nodos de texto para reconstruir la jerarquía del documento (por ejemplo, distinguir un `<li>` anidado dentro de un `<ol>`, el `href` de un `<a>` o el contenido que debe descartarse en `<script>`).

### 4. ¿Por qué no basta reemplazar etiquetas HTML con expresiones regulares?
Porque HTML es un lenguaje con estructura jerárquica y anidamiento arbitrario (una gramática libre de contexto con particularidades históricas), mientras que las expresiones regulares estándar solo reconocen lenguajes regulares. Reemplazar con regex falla ante etiquetas anidadas del mismo tipo (como `<ul>` dentro de `<ul>`), atributos en distinto orden o con comillas simples/dobles/omitidas, entidades HTML, comentarios, bloques `<pre>` donde no debe modificarse el contenido interno, o etiquetas mal cerradas que requieren recuperación de contexto.

### 5. ¿Qué dificultades presentan los elementos anidados?
Los elementos anidados obligan a mantener **estado y contexto durante el recorrido**:
- En listas anidadas (`<ul>`/`<ol>` dentro de `<li>`), hay que conservar el nivel de indentación y el contador independiente de cada `<ol>` sin que un subnivel reinicie o corrompa el nivel padre.
- En formato en línea (`<strong><em>texto</em></strong>` o `<a href="..."><strong>enlace</strong></a>`), debe preservarse el orden exacto de apertura/cierre de delimitadores y evitar espacios internos que invaliden el formato en Markdown.
- En bloques combinados (`<pre><code>...</code></pre>` o `<blockquote><p>...</p><ul>...</ul></blockquote>`), las reglas externas alteran cómo deben renderizarse los hijos internos (por ejemplo, no aplicar formato inline ni escape de caracteres dentro de `<pre>`, o anteponer `> ` a todas las líneas resultantes dentro de `<blockquote>`).

### 6. ¿Qué información puede perderse al convertir HTML a Markdown?
Markdown está orientado a estructura textual ligera, por lo que normalmente se pierde:
- Estilos visuales CSS (colores, fuentes, márgenes, cuadrículas, posicionamiento).
- Comportamiento dinámico e interactividad JavaScript (`onclick`, scripts embebidos, formularios complejos).
- Metadatos del `<head>`, atributos de diseño (`class`, `id`, `style`, `data-*`) y semántica avanzada de contenedores HTML5 (`<nav>`, `<aside>`, `<article>`, `<header>`, `<footer>`).
- Estructuras complejas sin equivalente directo en Markdown básico (iframes, video/audio embebido, multimedia interactiva o diseños con `<div>` complejos).

### 7. ¿Por qué deben resolverse las URL relativas?
En una página web, enlaces e imágenes suelen expresarse de forma relativa al documento o al dominio (por ejemplo, `guia.html`, `../img/logo.png` o `/docs/referencia`). El navegador puede resolverlas porque conoce la URL de origen, pero un archivo `.md` guardado localmente en el disco del usuario pierde ese contexto de red. Si no se resuelven contra la URL base durante la conversión, esos enlaces e imágenes quedarían rotos al abrir el archivo Markdown en IntelliJ IDEA o GitHub.

### 8. ¿Qué ventajas ofrece separar descarga, análisis y conversión en clases?
Aplicar la separación de responsabilidades (`WebClient`, `HtmlParser`, `MarkdownConverter`, `MarkdownWriter`, `HTML2Markdown`) aporta:
- **Mantenibilidad y legibilidad**: cada clase tiene una única razón de cambio (por ejemplo, ajustar timeouts HTTP no toca las reglas de Markdown).
- **Facilidad de prueba**: es posible probar `HtmlParser` y `MarkdownConverter` con cadenas HTML en memoria sin depender de red ni servidores reales.
- **Reutilización y extensibilidad**: se puede cambiar el origen de datos (archivo local vs. HTTP) o agregar nuevos formatos de salida sin reescribir toda la aplicación.
- **Manejo de errores más claro**: permite distinguir fallos de red, errores de parseo y errores de escritura en disco.

### 9. ¿Qué limitaciones presenta `ParserDelegator` con HTML moderno?
`javax.swing.text.html.parser.ParserDelegator` implementa un analizador heredado basado en el DTD de **HTML 3.2**:
- No reconoce nativamente etiquetas semánticas modernas de HTML5 (`<article>`, `<section>`, `<main>`, `<nav>`, `<figure>`, `<video>`, etc.), tratándolas como etiquetas desconocidas mediante `handleSimpleTag`.
- Puede emitir errores o advertencias ante atributos modernos (`data-*`, `aria-*`, `srcset`) o sintaxis HTML5 flexible (como `<a>` envolviendo bloques).
- Aplica reglas de recuperación heredadas (como generar etiquetas implícitas `<p>` o cerrar bloques automáticamente) que no coinciden al 100 % con el algoritmo estándar de parsing de HTML5.

### 10. ¿Qué modificaciones serían necesarias para convertir tablas HTML a Markdown?
Para soportar tablas (`<table>`, `<thead>`, `<tbody>`, `<tr>`, `<th>`, `<td>`) sería necesario:
1. **En `HtmlParser`**: asegurarse de capturar los nodos `table`, `thead`, `tbody`, `tr`, `th` y `td` en el árbol de elementos (actualmente ya son etiquetas reconocidas en HTML 3.2 por `ParserDelegator`, por lo que basta tratarlas en el conversor).
2. **En `MarkdownConverter`**:
   - Recolectar las filas (`<tr>`) y celdas (`<th>` / `<td>`) de la tabla antes de emitir la salida para conocer el número máximo de columnas.
   - Renderizar el contenido de cada celda en una sola línea (colapsando saltos de línea y escapando el carácter `|` como `\|` para no romper columnas).
   - Emitir la fila de encabezados (`| Col 1 | Col 2 |`), seguida obligatoriamente por la fila separadora Markdown (`| --- | --- |`, opcionalmente respetando alineación `align` con `:---`, `:---:`, `---:`), y luego cada fila de datos.
   - Manejar casos límite como tablas sin `<thead>` (usando la primera fila o una fila de encabezado vacía) o celdas con `colspan`/`rowspan`, que Markdown básico no representa directamente.
