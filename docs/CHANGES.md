# Registro de Cambios - FunshiEngineAI

## v0.11.0 (2026-10-20) — F9: verificador determinista de citas

### ✨ Nuevas Características

#### El "no inventar" deja de ser una instrucción blanda
- **`backend/rag/engine/CitationVerifier.kt`** (nuevo): comprueba, sin llamar al
  modelo ni a la red, que cada cita `[n]` de la respuesta esté respaldada por la
  fuente que cita. Es una función pura: se testea al 100% y nunca falla por
  culpa de la red.
- Tres criterios, en orden:
  1. **Literal**: el fragmento citado aparece en la fuente (tras normalizar:
     minúsculas, sin acentos, sin puntuación, camel case partido).
  2. **Sin detalles inventados**: los nombres propios y los números de dos
     dígitos o más que dice el fragmento tienen que estar en la fuente. Es lo
     que separa una paráfrasis de una invención: si el modelo escribió "usa
     DirectX 12" y la fuente dice "usa Vulkan", la cita no está respaldada
     aunque las frases se parezcan.
  3. **Paráfrasis**: al menos el 50% de las palabras significativas del
     fragmento aparecen en la fuente. Se aceptan plurales y variaciones de
     verbo ("renderiza" / "renderizar").
- Cada cita se reporta por separado con su fragmento, su fuente y el motivo
  si no está respaldada. El informe también lista las fuentes recuperadas que
  el modelo **no** usó.

#### Distintivo en el chat
- En modo Docs, tras cada respuesta la app verifica las citas y la burbuja
  muestra **"Citas verificadas (n)"** o cuántas quedan **sin respaldo** (y
  cuántas apuntan a una fuente que no existe).
- La verificación se guarda con el mensaje (`StoredCitation`), así que el
  distintivo sigue ahí al reabrir el chat.

### 🧪 Tests
- **280 tests** en total (backend 211, frontend 37, llm-api 32).
- `CitationVerifierTest` (backend, 27 casos): cita literal, paráfrasis,
  invención de nombre propio, invención de número, fuente inexistente,
  respuesta sin citas, cita repetida, acentos, plurales y variaciones.
- `ChatMessagesTest` — la verificación de citas viaja en los dos sentidos
  (UI ↔ disco).

### 📖 Documentación
- **`docs/MANUAL-RAG.md`** (nuevo): manual de uso del FunshiEngineAI como
  fuente RAG. Cubre los dos modos (General/Docs), de dónde sale el
  conocimiento, cómo se indexa y cómo escribir para que el recuperador
  encuentre lo que querés, cómo se citan las fuentes, qué significa el
  distintivo de verificación, las herramientas disponibles, la configuración
  de referencia, recetas y problemas frecuentes.

## v0.10.0 (2026-10-20) — F7: MCP preparado, configuración v4 y tests

### ✨ Nuevas Características

#### Contrato MCP (sin SDK todavía)
- **`backend/rag/mcp/McpToolAdapter.kt`** (nuevo): los contratos del protocolo
  MCP, sin depender del SDK oficial:
  - `McpConnection` — la interfaz del seam: `listTools()`, `callTool()`,
    `close()`.
  - `McpToolDescriptor`, `McpToolResult`, `McpContent` (texto y binario).
  - `McpToolAdapter` — convierte una tool de un servidor MCP en una tool de la
    app. El nombre se califica (`mcp__servidor__herramienta`) para que dos
    servidores puedan exponer tools homónimas sin pisarse.
- La tool remota **es una tool**: misma interfaz, mismo ciclo de permisos,
  mismos adjuntos. Lo único que cambia es la categoría (`MCP`, pide permiso) y
  el origen (`ToolOrigin.MCP`).
- El contenido binario que devuelve el servidor se guarda en la carpeta del
  chat y aparece como adjunto (igual que un PDF de `latex_compile`).
- **`backend/rag/mcp/McpManager.kt`** (nuevo): administra los servidores,
  arma los adaptadores y expone el estado de cada uno (conectado, sin
  conectar y por qué). Un servidor caído no impide que los demás funcionen.
- **`backend/rag/mcp/McpConfig.kt`** (nuevo): la sección `mcp` de la config
  (servidores declarados con id, transporte, comando/URL y variables de
  entorno).
- **El transporte todavía no está implementado** y la app lo dice: el conector
  por defecto (`PendingMcpConnector`) responde "falta el cliente oficial" y
  el estado de cada servidor lo muestra. Cuando llegue el SDK, sólo cambia el
  conector: el resto de la app no se toca.

#### Registro de herramientas con origen
- `Tool` ahora declara su **origen** (`ToolOrigin.LOCAL` o `ToolOrigin.MCP`).
- `ToolRegistry` puede filtrar por origen, agregar tools y **reemplazar** las
  de un origen (las tools MCP se redescubren en caliente).

#### Configuración v4
- Secciones nuevas en `app-config.json`:
  - `tools`: interruptor general y timeout por herramienta (60 s).
  - `outputs`: carpeta de archivos del chat y topes de tamaño (5 MB por
    archivo creado, 50 MB por descarga).
  - `latex`: motor (vacío = autodetectar), timeout y tope del documento.
  - `mcp`: servidores declarados.
- **Migración automática** desde `configVersion: 3` (sin tocar la config a
  mano): las secciones nuevas entran con los mismos valores que usaban las
  constantes del código, así que una instalación migrada se comporta igual.
- Los topes, el motor de LaTeX y la carpeta de salidas **dejan de ser
  constantes**: ahora salen de la config y se editan en **Ajustes →
  Herramientas**.
- El `AgentLoop` ahora corta una herramienta que se cuelga (timeout
  configurable) y le devuelve un error al modelo en vez de dejar el chat
  esperando.

#### Pantalla de herramientas
- **Ajustes → Herramientas** (nuevo): interruptor de herramientas, timeout,
  carpeta de salidas, topes de tamaño, motor de LaTeX y la lista de servidores
  MCP (agregar, editar, habilitar, quitar).
- **`/mcp`** (nuevo): muestra el estado de los servidores MCP y cuántas
  herramientas MCP hay registradas.

### 🧪 Tests
- **Infraestructura de tests** (JUnit 5) en los cuatro módulos JVM, con
  `useJUnitPlatform()` y AWT sin escritorio. Ver `docs/TESTING.md`.
- **247 tests**, todos deterministas (sin red, sin esperas):
  - backend (182): el bucle de tool calling (`AgentLoopTest`: qué se ejecuta,
    qué se devuelve al modelo, cuándo se rinde), permisos, sanitizador, parser
    del protocolo por prompt, `latex_compile` con un motor TeX falso, tools de
    archivos y web, registro de tools, adaptador y administrador MCP, migración
    de configuración, troceador, índice BM25, base de conocimiento, motor de
    RAG y persistencia de chats.
  - frontend (33): segmentación de matemática y render, parser de markdown.
  - llm-api (32): validación fail-fast de la config del LLM y el gateway
    OpenAI-compatible (SSE, acumulación de `tool_calls`, body del request y
    clasificación de errores) contra un **servidor HTTP local** del JDK.

### 🐛 Correcciones
- **El tope de errores de herramienta nunca se alcanzaba** (F1, grave): el
  contador de fallos consecutivos se ponía en cero *al empezar* cada ronda, así
  que nunca llegaba al máximo de 3. Con una tool que fallara siempre, el bucle
  seguía dando vueltas hasta el límite de iteraciones (8) en vez de rendirse
  después de 3. Ahora sólo un **éxito** corta la racha de errores. Lo detectó
  `AgentLoopTest`.
- **Sanitizador de tool calls**: la reparación de JSON nunca se probaba cuando
  el parseo leniente tenía éxito pero la **forma** era inválida. Con comillas
  curvas el parser leniente acepta `“tool”` como clave, la forma fallaba y la
  reparación se saltaba: la llamada se rechazaba aunque el modelo la hubiera
  escrito bien. Ahora, si la forma no valida, se prueba la versión reparada
  antes de rendirse.

### 🔒 Sin cambios de comportamiento visible
- Sin `configVersion: 4`, una config v3 migrada se comporta **exactamente**
  igual que antes (mismos topes, mismo motor autodetectado, mismos permisos).
- Las herramientas MCP siguen pidiendo permiso como cualquier otra
  (`/permitir-mcp`), apagado por defecto y por chat.
- La carpeta de archivos del chat sigue siendo `app-home/outputs/<chat-id>/`.

## v0.9.0 (2026-10-20) — F5: matemática en el chat y compilación LaTeX → PDF

### ✨ Nuevas Características

#### F5a — La matemática se dibuja en el chat (JLaTeXMath, offline)
- **`frontend/app/ui/MathRender.kt`** (nuevo): renderiza una fórmula TeX a una
  imagen **en memoria** con **JLaTeXMath 1.0.7** (biblioteca de Java pura, sin
  TeX instalado y sin red). La imagen se recolorea al `onSurface` del tema, así
  que se lee igual en claro y en oscuro.
- **`frontend/app/ui/Markdown.kt`**: el parser de markdown reconoce la
  matemática como un nodo más (`MdNode.Math`). La fórmula inline (`$…$`) se
  dibuja con `appendInlineContent` en medio del párrafo; la de display
  (`$$…$$`) va como bloque centrado.
- Reglas de parsing, pensadas para no romper la prosa:
  - El `$` de cierre **no puede** ir seguido de un dígito: `$5 … $10` es texto
    común, no una fórmula.
  - Un `$` sin cerrar se queda como texto plano: con streaming es lo normal y
    no debe romper el párrafo a medio render.
  - El código en línea (`` `$x$` ``) nunca se interpreta como matemática.
- **No hace falta TeX**: la matemática del chat funciona aunque no haya ningún
  motor de LaTeX instalado (que es justo el caso de la máquina donde se
  desarrolló).

#### F5b — `latex_compile`: de LaTeX a PDF
- **`backend/rag/agent/tools/LatexTool.kt`** (nuevo): tool de la categoría
  **FILES**, así que pide permiso como `create_file` (apagado por defecto, se
  concede por chat con `/permitir-archivos`).
- **Busca el motor en el PATH**: `pdflatex` primero, `xelatex` después (sin
  lanzar procesos: sólo mira el disco). El modelo puede pedir uno u otro con
  el argumento `engine`; si pide uno que no está instalado, el error lo dice.
- **Fail-fast**: si no hay ningún motor, la herramienta devuelve un error con
  la **guía de instalación** (TeX Live o MiKTeX) en lugar de un PDF que no
  sale. Es el mismo patrón del proyecto: fallar claro y temprano.
- **Documentos parciales**: si el modelo manda un fragmento sin
  `\documentclass`, se le envuelve en un preámbulo estándar (`article`,
  `inputenc`, `amsmath`, `geometry`). Con `\documentclass` se usa tal cual.
- **Directorio de trabajo temporal**: la compilación corre en una carpeta
  aparte, así que los `.aux`, `.log` y compañía no ensucian la carpeta del
  chat. Al final sólo quedan el `.tex` y el `.pdf`.
- **Salida**: `ToolResult.Ok` con dos `Artifact` — el PDF (`application/pdf`)
  y el código fuente (`application/x-tex`) — que la UI muestra como tarjetas
  de adjunto (Abrir / Guardar).
- **Límites**: 1 MB de documento, 60 s de compilación y 60 caracteres para el
  nombre del archivo (el nombre se sanea: no puede traer rutas).

### 🔧 Cambios internos
- `ToolRegistry` arranca con `get_time`, `create_file`, `download_url`,
  `web_search`, `web_fetch` y `latex_compile`.
- `ToolContext` lleva `outputsDir`, así que `latex_compile` escribe en la
  carpeta del chat (`app-home/outputs/<chat-id>/`) y no en cualquier lado.
- Todo el I/O de `latex_compile` (escribir, compilar, copiar) corre en
  `Dispatchers.IO`.
- Dependencia nueva en `frontend`: `org.scilab.forge:jlatexmath:1.0.7`.

### 🔒 Sin cambios de comportamiento visible
- `configVersion` sigue en 3 (la config v4 llega en F7).
- Sin permiso `FILES` concedido, `latex_compile` no llega a ejecutarse.
- La matemática se dibuja siempre que el modelo escribe `$…$`: no depende de
  permisos ni de que haya TeX instalado.

---

## v0.8.0 (2026-10-20) — F4: búsqueda en la web y lectura de páginas

### ✨ Nuevas Características

#### `web_search`: buscar en la web sin clave de API
- **`backend/rag/agent/tools/WebTools.kt`** (nuevo): contrato `SearchProvider`
  (`search(query, limit)` → `List<SearchResult>`) y el resultado con `source`
  (de qué motor salió), para que el modelo pueda citarlo.
- **`DuckDuckGoProvider`** (principal): usa el HTML de `html.duckduckgo.com` y,
  como último recurso, la API de *instant answer*. Sin clave y sin cuenta.
- **`WikipediaProvider`** (respaldo): API oficial de Wikipedia en `es` y `en`,
  sin clave y sin control anti-bot.
- **`ChainSearchProvider`**: encadena los anteriores y gana el primero que
  responda. Así la herramienta **siempre** devuelve algo.
- **`WebSearchTool`**: devuelve hasta 8 resultados con título, URL, fragmento y
  motor; si no hay nada, se lo dice al modelo para que pruebe otros términos.

DuckDuckGo no publica una API sin clave y su HTML tiene un control anti-bot:
contesta con una página de verificación (HTTP 202) en lugar de resultados. El
proveedor lo sortea en tres pasos:

1. **Prepara** el cliente: visita la portada una vez y conserva un cookie jar
   en memoria (así el servidor ve una sesión normal).
2. **Reintenta**: si llega la verificación, espera 800 ms y vuelve a pedir
   (hasta 3 intentos; la verificación es intermitente).
3. **Respalda**: si aun así no hay nada, la API de instant answer.

Los anuncios patrocinados del buscador (`duckduckgo.com/y.js`) se descartan:
no son resultados orgánicos.

#### `web_fetch`: leer una página y devolverla como texto
- Trae la URL con el `HttpClient` del JDK (timeout 20 s, tope de 2 MB),
  reconoce el charset de la respuesta y sigue redirecciones.
- Convierte el HTML en texto legible: saca `script`, `style`, `noscript`,
  `svg` y `head`, pone saltos de línea en los bloques y resuelve las entidades
  HTML.
- **Detecta binarios**: si la URL es un PDF o una imagen, la herramienta no
  devuelve basura — avisa que use `download_url`, que lo baja y lo deja como
  adjunto en el chat.
- User-Agent de navegador: muchos sitios rechazan los clientes que no se
  parecen a uno.

#### Permisos de la categoría `WEB`
- `web_search` y `web_fetch` son de la categoría `WEB`: piden permiso como las
  de archivos (apagado por defecto, se concede por chat con `/permitir-web`).
  La puerta sigue siendo la misma: `ToolPermissions.authorize()`.

### 🔧 Cambios internos
- **Decodificación de entidades HTML** (`decodeEntities`): tabla de entidades
  nombradas (Latin-1, puntuación, símbolos y griegas) más las numéricas
  (`&#160;`, `&#xA9;`). Se resuelve todo en **una sola pasada**, así
  `&amp;lt;` queda `&lt;` y no `<` (decodificar dos veces rompe el texto). Los
  nombres distinguen mayúsculas: `&Aacute;` es Á y `&aacute;` es á.
- `ToolRegistry` arranca con `get_time`, `create_file`, `download_url`,
  `web_search` y `web_fetch`.
- Todo el I/O de las herramientas web corre en `Dispatchers.IO`.

### 🔒 Sin cambios de comportamiento visible
- `configVersion` sigue en 3 (la config v4 llega en F7).
- Sin permiso `WEB` concedido, las herramientas web no llegan a ejecutarse.

---

## v0.7.0 (2026-10-20) — F3: herramientas de archivos, permisos por categoría y adjuntos

### ✨ Nuevas Características

#### Permisos de herramientas: por categoría, apagados por defecto y por chat
- **`ToolCategory`** (`backend/rag/agent/Tool.kt`): `NONE` (sin efecto externo),
  `FILES`, `WEB`, `MCP`. El permiso es **por categoría**, no por herramienta:
  conceder `FILES` habilita `create_file` y `download_url`.
- **`ToolPermissions`** (`backend/rag/agent/ToolPermissions.kt`, nuevo): única
  puerta de autorización. `NONE` pasa siempre; una categoría ya concedida pasa
  sin preguntar; si no, se pide permiso al usuario.
- **`ToolContext`** lleva `chatId`, `outputsDir`, `grantedCategories` y un
  callback `requestPermission`. `PermissionRequest` (herramienta, categoría,
  resumen legible y argumentos crudos) es lo que la UI muestra.
- **Decisiones**: `ALLOW_ONCE` (sólo esa llamada), `ALLOW_ALWAYS` (además
  concede la categoría en ese chat) y `DENY`.
- **Semántica de la denegación**: la primera denegación **no** cuenta como
  error de herramienta — el modelo recibe el feedback y sigue conversando. Si
  insiste, la segunda llamada se deniega automáticamente (sin repetir el
  diálogo) y el bucle corta.
- **Por chat**: el estado vive en el nodo del chat (`StoredNode.grantedCats`) y
  se persiste en `config/chats.json`.

#### Herramientas de archivos (`backend/rag/agent/tools/FileTools.kt`, nuevo)
- **`create_file`**: escribe un archivo de texto o código en
  `app-home/outputs/<chat-id>/`. Validación estricta: el nombre no puede traer
  separadores ni `..`, la extensión tiene que estar en la lista permitida
  (`.py`, `.kt`, `.cpp`, `.glsl`, `.html`, `.css`, `.js`, `.json`, `.rs`…) y el
  contenido no puede pasar los **5 MB**. El modelo decide el nombre, nunca la
  carpeta.
- **`download_url`**: descarga una URL `http/https` a la misma carpeta, con
  tope de **50 MB**, escritura en `.part` y movimiento atómico al terminar, y
  `Mutex` por ruta (dos descargas al mismo archivo no se pisan). El nombre sale
  del `Content-Disposition` o de la URL; si falta la extensión, se deduce del
  `Content-Type`.
- Ambas herramientas son de la categoría `FILES` (piden permiso).

#### Adjuntos descargables en el chat
- **`Artifact`** en el contrato de herramientas: `ToolResult.Ok` puede llevar
  una lista de archivos producidos. `AgentEvent.ToolEnd` y `ExecutedTool` los
  propagan hasta la UI.
- **`UiAttachment` / `StoredAttachment`**: el adjunto se persiste con el
  mensaje (`StoredMessage.attachments`) y se dibuja en la burbuja.
- **`app/chat/AttachmentCard.kt`** (nuevo): tarjeta con ícono, nombre, tamaño y
  tipo MIME, y dos acciones — **Abrir** (con la app del sistema) y **Guardar…**
  (copia donde elija el usuario, con `JFileChooser` en el hilo de Swing).

#### Diálogo de permiso y comandos
- **`app/chat/PermissionDialog.kt`** (nuevo): `AlertDialog` con el resumen de
  la llamada, los argumentos crudos y tres respuestas — **Permitir siempre** /
  **Una vez** / **Cancelar**.
- **`app/chat/ChatPermissions.kt`** (nuevo): estado del permiso, granting /
  revoke por categoría y el handshake `askPermission` / `answerPermission`
  (`CompletableDeferred`; si se cancela la generación con el diálogo abierto,
  el diálogo se cierra y la cancelación se propaga).
- **Comandos**: `/permitir-archivos`, `/permitir-web`, `/permitir-mcp`,
  `/revocar-archivos`, `/revocar-web`, `/revocar-mcp` y `/permisos` (estado
  del chat activo). `/help` actualizado.
- **Cabecera del chat**: la etiqueta **"Permisos: …"** muestra las categorías
  concedidas en el chat activo.

### 🔧 Cambios internos
- `Tool` gana `category` y `describeCall(args)` (resumen legible para el
  diálogo); `ToolResult.Ok` gana `artifacts`.
- El `AgentLoop` aplica la puerta de permisos antes de ejecutar cada
  herramienta y lleva la cuenta de denegaciones.
- `ToolRegistry` arranca con `get_time`, `create_file` y `download_url`.
- HTTP con el cliente del JDK (`java.net.http.HttpClient`), igual que
  `rag/retrieval/Sources.kt`: el backend no depende de Ktor.

### 🔒 Sin cambios de comportamiento visible
- Sin herramientas de categoría sensible, el bucle se comporta igual que antes.
- `configVersion` sigue en 3 (la config v4 llega en F7); `chats.json` es
  tolerante con chats viejos (los que no tienen `grantedCats` arrancan sin
  permisos concedidos).

---

## v0.6.0 (2026-10-20) — F2: markdown y bloques de código + arquitectura modular

### ✨ Nuevas Características

#### Las respuestas del modelo se renderizan como markdown
- **`app/ui/Markdown.kt`** (nuevo): renderizador propio, sin dependencias externas.
  `parseMarkdown(src)` devuelve un árbol de `MdNode` (`Heading`, `Paragraph`, `Bullets`,
  `Code`, `Quote`, `Table`, `Divider`) y `MarkdownText` lo pinta con `AnnotatedString`.
  Los estilos en línea (`**negrita**`, `` `código` ``, `[enlace](url)`) se resuelven en
  `buildInlineAnnotated`.
- **`app/ui/CodeBlock.kt`** (nuevo): bloque de código con tokenizador y resaltado
  propios, botón **copiar** y **guardar como…** con la extensión real del lenguaje
  (`.py`, `.kt`, `.cpp`, `.glsl`, `.html`, `.css`, `.js`, `.json`, `.rs`…), con
  `JFileChooser` en el hilo de Swing.
- En `ChatPane` las respuestas del modelo pasan por `MarkdownText`; los mensajes del
  usuario siguen siendo texto plano.

### 🔧 Arquitectura: un paquete por área

- **Paquetes** reorganizados en los tres módulos (mapa completo en
  `docs/ARCHITECTURE.md`):
  - `llm-api`: `llm.api` (gateway + contratos de tools), `llm.config`, `llm.error`,
    `llm.local`, `llm.catalog`, `llm.service`.
  - `backend`: `rag.config`, `rag.store`, `rag.retrieval`, `rag.engine`, `rag.agent`.
  - `frontend`: `app` (entrada + navegación), `app.core` (estado compartido),
    `app.ui` (markdown/código), `app.chat` (chat), `app.screens` (modelos, APIs,
    ajustes).
- **Archivos grandes partidos**:
  - `ChatPane.kt` → `ChatPane` + `ChatSidebar` + `MessageBubble` + `ToolCallCard` +
    `ComposerBar`.
  - `ChatViewModel.kt` (1.158 líneas) → `ChatViewModel` (núcleo: estado, tema y flujo
    del chat) + `ChatLibrary` (árbol de chats y persistencia) + `ChatEngine` (arranque,
    gateway, documentación por motor) + `ChatCatalog` (modelos y APIs) +
    `ChatCommands` (comandos de barra) + `ChatMessages` (mapeo UI ↔ persistido).
    Como Kotlin no tiene `partial class`, las operaciones de cada área son funciones
    de extensión sobre el ViewModel: el estado sigue siendo del ViewModel, lo que se
    reparte es el código.
- Los miembros que hacen falta desde varias áreas pasan a `internal` (visibles sólo
  dentro del módulo del frontend); las pantallas importan explícitamente las
  extensiones que usan.

### 📚 Documentación
- `docs/ARCHITECTURE.md` reescrito: mapa de módulos y paquetes, flujo de tool calling
  (nativo vs. prompt, sanitizador y reglas del bucle), render markdown/código,
  organización del frontend, concurrencia, config y errores.
- `docs/TAREAS.md`: F2 marcado como implementado + tarea de modularización.
- `README.md` actualizado (estructura, pantallas, config, roadmap).

### 🔒 Sin cambios de comportamiento visible
- El modelo sigue hablando igual: el markdown sólo cambia **cómo se ve** la
  respuesta. Sin herramientas registradas el bucle es streaming de texto puro.
- `configVersion` sigue en 3 (la v4 llega con F7); ninguna migración nueva.

---

## v0.5.0 (2026-10-20) — F1: núcleo de tool calling

### ✨ Nuevas Características

#### Tool calling: el modelo ya puede pedirle acciones a la app
- **`llm-api`**: streaming tipado `Flow<LlmChunk>` (`Text` / `ToolCall`); el
  gateway OpenAI-compatible envía `tools[]` en el request, acumula los
  `delta.tool_calls` fragmentados del SSE y emite la llamada completa.
  `ChatMessage` extendido con `toolCalls`, `toolCallId` y `toolName` (protocolo
  OpenAI de resultados `role=tool`).
- **`backend/rag/agent/`** (nuevo):
  - `Tool` + `ToolSpec` + `ToolResult` (Ok/Error tipado) y `ToolContext`.
  - `ToolRegistry`: catálogo por nombre, acepta herramientas locales y (futuro)
    adaptadas de MCP.
  - `AgentLoop`: bucle modelo→herramienta→modelo (máx. 8 rondas), con los DOS
    caminos del diseño híbrido:
    - `NATIVO` en modo REMOTE (OpenAI/Groq/OpenRouter…): `tool_calls` del protocolo.
    - `POR PROMPT` en LOCAL (Phi-4-mini en llama-server): catálogo inyectado en el
      system prompt + bloque delimitado:
      ````
      ```tool_call
      {"tool":"nombre","arguments":{...}}
      ```
      ````
      y parser incremental `PromptToolParser` que retira esos bloques del texto
      visible durante el streaming.
  - `GetTimeTool`: herramienta de prueba sin permisos (fecha y hora del sistema).
- **Combo con RAG intacto**: `RagEngine.prepare()` arma system+contexto+historial;
  el loop usa esos mensajes y agrega sus rondas. `Answer`/`ask()` siguen exigiendo
  fuentes en modo DOCS.
- **UI**: tarjeta `ToolCallCard` dentro de la burbuja (nombre + argumentos +
  "Ejecutando…"/OK/Falló + resultado); persistida en `chats.json`
  (`StoredMessage.toolCards`, tolerante con chats viejos). El "Detener" corta el
  bucle completo.
- **Comando `/tools`**: lista las herramientas registradas; `/help` actualizado.

### 🔒 Sin cambios de comportamiento visible
- Sin herramientas registradas el loop es streaming de texto puro (idéntico a antes).
- `configVersion` sigue en 3 aún (config v4 llega en F7); ninguna migración nueva.

---

## v0.4.0 (2026-10-18) — Chat sin límite de tiempo, /help y encolado de mensajes

### ✨ Nuevas Características

#### Sin límite de tiempo por respuesta
- `chatTimeoutMs` pasa a **0 = sin límite** (antes 90 000 ms): una respuesta larga o un modelo lento ya no se corta con TIMEOUT.
- `LlmGateway.chatStream()` aplica `withTimeout` **sólo si `chatTimeoutMs > 0`**; con 0 la petición corre sin tope.
- Validación relajada: `chatTimeoutMs` sólo puede ser negativo para ser inválido (0 es válido).
- **Migración de config v2 → v3**: las instalaciones existentes con `90000` guardado lo migran a `0` automáticamente.

#### Comando `/help`
- `/help` lista todos los comandos (`/model`, `/model <id>`, `/thinking`, `/power`) con su uso, sin tocar el modelo.
- El placeholder del redactor ahora sugiere `/help para ver comandos`.

#### Encolado de mensajes mientras la IA responde
- El redactor **ya no se bloquea** al enviar: permanece habilitado y con el foco mientras el modelo genera.
- Lo que se escribe durante la respuesta se **encola** y **se ve en una lista** con cada texto (no sólo un contador), con "Quitar" por mensaje y "Descartar todo".
- Al terminar la respuesta, todo lo encolado se envía **como un solo texto**, un mensaje debajo del otro (`joinToString("\n\n")`).
- "Detener" descarta también la cola (nada se dispara solo después de una cancelación).

#### Acciones sobre los mensajes: Copiar y Reiniciar
- **Copiar**: botón en todas las burbujas (usuario y respuesta de la IA) que copia el contenido al portapapeles.
- **Reiniciar**: botón en la **última respuesta entregada** por el modelo que la descarta y reenvía el mensaje del usuario que la provocó, conservando el historial previo (misma semántica que "Editar y reenviar": el turno completo se repite). Inactivo mientras el modelo sigue generando.

### 🔧 Mejoras de UI/UX
- El chat **baja solo con la respuesta** durante el streaming, salvo que el usuario haya subido a leer (no se lo arrastra).
- Al entrar al chat o cambiar de conversación, la lista arranca **abajo del todo**.
- Scroll-to-bottom real: además de `scrollToItem` se empuja el scroll hasta el final del último mensaje (que puede ser más alto que el viewport).
- El foco se conserva en el redactor tras enviar (botón Enviar o Enter).
- **Scrollbar en la lista de chats** del sidebar (antes sólo la tenía el área de mensajes).
- **Scrollbar de mensajes con largo estable**: el thumb depende sólo del **largo total del chat**, no del mensaje que tiene al lado. El adapter oficial de Compose estima el total como `promedio(ítems visibles) * cantidad`, así que al desplazarse el thumb saltaba de ~32px (respuesta larga a la vista) a ~350px (mensajes cortos). El nuevo `StableChatScrollbarAdapter` acumula el tamaño medido de cada mensaje y estima los no vistos con el promedio de los ya medidos: el total converge al real a medida que se recorre la conversación.
- El scrollbar de mensajes tiene estilo propio: grosor 12dp, esquinas redondeadas, mínimo 24dp y colores de reposo/hover con la paleta actual.

### 🐛 Correcciones
- **El mensaje enviado ya siempre se ve**: al enviar, el chat baja al final incondicionalmente; el chequeo "¿está abajo?" sólo se aplica durante el streaming (justo después de agregar mensajes, el layout aún no los medía y el auto-scroll se saltaba el mensaje recién enviado).
- **Borrar chats funciona mientras la IA responde**: antes `deleteNode()` abortaba en silencio con `busy=true` (el diálogo se cerraba y nada se borraba). Ahora borra chats ajenos sin interrumpir la respuesta y, si el subárbol contiene al chat activo, corta la generación antes de borrar.
- **Una respuesta cancelada por borrar/cambiar de chat ya no se escribe en otro chat**: los callbacks de streaming guardan el chat de origen y sólo actualizan si el chat activo sigue siendo el mismo.
- **No se arrastra al usuario al final mientras la IA escribe**: el flag que gobierna el auto-follow sólo cambia cuando se mueve la **posición** de scroll (subir o bajar del usuario o nuestro). Antes dependía de "¿el último mensaje está a la vista?", y con una respuesta más alta que el viewport ese chequeo se mantenía en true: cada token volvía a arrastrar al fondo y era imposible leer la respuesta mientras se escribía.

---

## v0.3.0 (2025-10-18) — Motor de inferencia centralizado

### ✨ Nuevas Características

#### Pantalla "Motor" (antes "APIs") — gestión central del modo LOCAL/REMOTE
- Sidebar renombrado: `APIs` → **`Motor`** (colapsado: `Mot`), coherente con el título "Motor de inferencia".
- **Segmentado LOCAL/REMOTE** en la tarjeta de estado: "Modelo local" / "API remota".
  - "API remota" llama a `vm.setConfigMode("REMOTE")`, que **sincroniza el motor con lo que muestra la pantalla** (si no hay APIs guardadas muestra el error correcto y no cambia de modo).
  - "Modelo local" llama a `vm.useLocal()`.
- **Formulario dinámico**: en REMOTE muestra la colección de APIs + formulario de alta (nombre, URL base, modelo, clave) con atajos de proveedores (OpenAI, Groq, OpenRouter, Ollama, LM Studio); en LOCAL muestra el modelo local activo.
- **Prellenado**: los 4 campos del formulario arrancan con la API activa (o la primera guardada) vía `remember(vm.activeApiId, vm.apiPresets)`.
- **`listModels()` + botón "Probar conexión"**: `ChatViewModel.testApi()` hace `GET /models` contra el endpoint y reporta cuántos modelos devuelve. Envolto en `withTimeout(20_000)` + timeout friendly en 20 s si el endpoint no responde.

#### Boot por fases con porcentajes
- `boot()`: indexar docs → descargar modelo (%, MB/MB) → clonar/compilar llama.cpp si falta (%, sólo si no está) → cargar modelo en memoria (%). Cada fase emite `UiProgress` para la UI.

#### Suppliers de config `() -> LlmConfig`
- `LlmService` y `ModelManager` reciben un **supplier** en vez de una copia: los servicios leen la config en cada arranque. Sin esto, activar una API/modelo no tenía efecto en el arranque en curso.

#### Migración de config v1 → v2 (`configVersion`)
- `buildFromSource` pasa a `true` por defecto en v2 (v1 lo tenía `false`) y se eliminan las APIs localhost prefijadas que forzaban el modo remoto a un servidor local vacío.

#### LOCAL: "Descargar y usar"
- Los modelos no descargados ofrecen **"Descargar y usar"** (`downloadModel(entry, activateAfter = true)`: descarga → activa → reinicia el motor) y "Descargar" por separado. Los ya descargados usan "Usar".

### 🔧 Mejoras de UI/UX
- Layouts con **scrollbar** vertical (`VerticalScrollbar` + `rememberScrollbarAdapter`) en Motor, Config y Modelos.

### 🩹 Correcciones
- `LlmGateway.kt`: faltaba `import kotlinx.coroutines.withContext` → `listModels()` no compilaba.
- `ChatViewModel`: choque de firma JVM `setLocalPort`/`setBuildFromSource` (propiedad + función homónima) → renombradas a `changeLocalPort`/`changeBuildFromSource`.
- `testApi()`: timeout friendly en 20 s y re-lanzado real de cancelaciones (`CancellationException`).

---

## v0.2.0 (2025-10-18) — Arquitectura Reactiva + Sidebar Colapsable

### ✨ Nuevas Características

#### Sidebar Colapsable (`NavigationRailPane`)
- Ancho expandido: 200dp (etiquetas completas) / Colapsado: 72dp (letras C/M/A/⚙)
- Botón ≡/☰ para alternar con animación `animateContentSize()`
- Estado persistido en memoria durante la sesión

#### Chat No Bloqueante (Arquitectura Reactiva)
**Problema resuelto**: La UI se quedaba congelada en "Preparando el asistente..." descargando modelo (2.4 GB) + compilando llama.cpp antes de mostrar nada.

**Solución implementada**:
1. `ChatViewModel.init`: `screen = Screen.CHAT` por defecto (no `LOADING`)
2. `boot()` se lanza en background (`scope.launch`) — NO bloquea el hilo principal
3. Nueva cola `pendingMessages: MutableList<Pair<String, List<ChatMessage>>>()`
4. `send()`:
   - Si `engine != null && ready` → `sendInternal()` inmediato (streaming)
   - Si NO → encola `(text, history)` + muestra placeholder "…" en UI
5. `boot()` al terminar → `ready = true` → `processPendingMessages()` vacía la cola en orden FIFO
6. Banner superior `ModelLoadingBanner` informa progreso sin tapar el chat

#### Componentes UI Separados (Modularización)
| Archivo | Responsabilidad |
|---------|-----------------|
| `UiState.kt` | Tipos compartidos (`Screen`, `UiMessage`, `UiProgress`, `AppTheme`) |
| `NavigationRailPane.kt` | Sidebar colapsable animado |
| `ChatPane.kt` | UI chat: burbujas, input, cancelar/enviar |
| `ModelsPane.kt` | Catalogo modelos: descargar/activar/agregar HF |
| `ApisPane.kt` | APIs OpenAI-compat: guardar/activar/volver a local |
| `SettingsPane.kt` | Tema claro/oscuro, color acento, modo LOCAL/REMOTE |
| `SettingsComponents.kt` | `themeOption`, `RadioGroup` reutilizables |
| `LoadingPane.kt` | Pantalla carga completa (fallback + retry) |
| `ModelLoadingBanner.kt` | Banner no intrusivo durante carga background |
| `ChatScreen.kt` | Layout raíz: sidebar + contenido + banner condicional |
| `ChatViewModel.kt` | Lógica de negocio + estado reactivo + cola mensajes |

### 🔧 Correcciones Técnicas

- **Versiones Compose compatibles**: `compose.materialIconsExtended` vía BOM (no hardcodeado 1.7.0) → fix `NoSuchMethodError: Constraints.fitPrioritizingWidth` con Kotlin 2.1.0 / Compose Compiler 2.1.0
- **Imports limpiados**: Eliminados `material.icons.filled.*` / `outlined.*` no resueltos; usan `Icons.Filled.*` / `Icons.Outlined.*`
- **build.gradle.kts frontend**: Dependencia `compose.materialIconsExtended` del BOM de Compose Desktop 1.7.3

### 📚 Documentación

- `docs/ARCHITECTURE.md`: Documentación completa de arquitectura (módulos, flujo datos, hilos, estado, config, errores, extensibilidad)

---

## v0.1.0 (2025-10-17) — Versión Inicial Modular

- Frontend modularizado en 10 archivos Kotlin separados
- Backend RAG con BM25 + fuentes GitHub/locales
- llm-api con LOCAL (llama.cpp clonado/compilado) + REMOTE (OpenAI-compat)
- Gestión modelos: catálogo HF, descarga streaming + reanudación
- Config persistida JSON + system prompt + carpeta docs internas
- Tema dinámico claro/oscuro + color acento
- Validación fail-fast (`LlmConfig.validate()`)

---

## Próximos Pasos (Roadmap)

- [ ] Tests unitarios para `ChatViewModel` (cola mensajes, boot, cancel)
- [ ] Tests de integración para `KnowledgeBase.sync()` + `RagEngine.ask()`
- [ ] Soporte multi-ventana / pestañas de chat
- [ ] Exportar/importar conversaciones (JSON/Markdown)
- [ ] Plugin system para fuentes RAG personalizadas
- [ ] Métricas de latencia / tokens por segundo en UI