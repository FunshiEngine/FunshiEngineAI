# Arquitectura de FunshiEngineAI

## Visión General

FunshiEngineAI es una aplicación de escritorio (Compose Multiplatform / Desktop) para chat RAG
(Retrieval-Augmented Generation) con modelos LLM locales o remotos, que además puede dar
**herramientas** al modelo (tool calling) y **renderizar** markdown y código.

La arquitectura sigue principios de **separación de responsabilidades** (paquetes por área),
**reactividad** (Compose + `MutableState`), **fail-fast** (config validada antes de tocar red o
procesos) y **tolerancia a la entrada del modelo** (el bucle de herramientas repara y reintenta en
lugar de fallar).

---

## Módulos del Proyecto

```
FunshiEngineAI/
├── frontend/          # UI Compose Desktop (Kotlin)
├── backend/           # Motor RAG + bucle de tool calling
├── llm-api/           # Capa de inferencia LLM (local + remoto)
├── estructuras/       # Estructuras de datos reutilizables (árboles, colas, pilas)
└── app-home/          # Directorio de trabajo en tiempo de ejecución (config, modelos, logs, docs)
```

Dependencias (una sola dirección, sin ciclos):

```
frontend  ──►  backend  ──►  llm-api
   └──────────────────────────►  (backend usa llm-api; el frontend usa ambos)
```

`estructuras` es una biblioteca independiente; la usa el frontend (árbol de chats).

---

## Mapa de paquetes

Cada módulo agrupa su código en sub-paquetes por responsabilidad. Los nombres de archivo y de
paquete cuentan la historia: no hace falta buscarlos en un mapa mental de "frontend".

### `llm-api` — capa de inferencia

| Paquete | Contenido |
|---------|-----------|
| `llm.api` | `LlmGateway` (interfaz), `OpenAiCompatibleGateway` (SSE), `ToolSpec`, `LlmChunk` |
| `llm.config` | `LlmConfig`, `ApiPreset`, `ModelPreset`, `ChatMessage`, `LlmMode` |
| `llm.error` | `LlmException`, `LlmErrorCode` (errores tipados con código y `retryable`) |
| `llm.local` | `LlamaManager` (clone/build de llama.cpp), `LocalServerManager` (proceso, puerto, health) |
| `llm.catalog` | `ModelManager`, `HuggingFaceDownloader`, entradas de catálogo (`ModelEntry`, `HfGgufFile`…) |
| `llm.service` | `LlmService`: fachada que une gateway + modelo local y decide LOCAL/REMOTE |

`ToolSpec` vive en `llm-api` (y no en `backend`) porque es el gateway quien serializa el catálogo
en el request `tools[]`; la dependencia va en un solo sentido.

### `backend` — RAG y agente

| Paquete | Contenido |
|---------|-----------|
| `rag.config` | `AppConfig`, `AppPaths`, `RagConfig`, perfiles de color, `ToolsConfig`/`OutputsConfig`/`LatexConfig` (F7) |
| `rag.store` | `ChatStore`: persistencia del árbol de chats (`config/chats.json`) |
| `rag.retrieval` | `Chunker`, `Bm25Index`, `KnowledgeBase`, `Sources`, modelos de documento |
| `rag.engine` | `RagEngine`: arma el prompt (system + contexto + historial) y responde; `CitationVerifier` (F9): comprueba las citas de la respuesta contra sus fuentes |
| `rag.agent` | Tool calling: `Tool`, `ToolResult`, `ToolContext`, `ToolRegistry`, `AgentLoop`, `PromptToolParser`, `ToolCallSanitizer`, `ToolPermissions`, `tools/GetTimeTool`, `tools/FileTools`, `tools/WebTools`, `tools/LatexTool` |
| `rag.mcp` | MCP (F7): `McpConnection` (contrato), `McpToolAdapter`, `McpManager`, `McpConfig` |

### `frontend` — UI

| Paquete | Contenido |
|---------|-----------|
| `app` | `Main` (ventana, tema), `NavigationRail` (sidebar colapsable) |
| `app.core` | Tipos compartidos: `UiState.kt` (`Screen`, `UiMessage`, `UiToolCall`, `UiProgress`), `ChatTree.kt` (`ChatNode`, `ChatRow`, `ChatTree`) |
| `app.ui` | Render de contenido: `Markdown.kt` (parser + `MarkdownText`), `CodeBlock.kt` (resaltado, copiar, guardar), `MathRender.kt` (F5: TeX → imagen con JLaTeXMath) |
| `app.chat` | La pantalla de chat: `ChatViewModel` + áreas, `ChatCommands`, `ChatMessages`, `ChatPane`, `ChatSidebar`, `MessageBubble`, `ToolCallCard`, `ComposerBar`, `ChatScreen`, `ChatScrollbarAdapter`, `LoadingPane`, `ChatTools` (F7) |
| `app.screens` | `ModelsScreen`, `ApisScreen`, `SettingsPane`, `SettingsComponents`, `ToolsSettings` (F7) |

---

## Tool calling: el modelo pide acciones a la app

El bucle vive en `rag.agent.AgentLoop` y funciona igual en los dos motores, cambiando sólo **cómo
llegan** las llamadas.

### Modo nativo (REMOTE, API compatible con OpenAI)

1. `AgentLoop` pasa el catálogo (`List<ToolSpec>`) al gateway.
2. `OpenAiCompatibleGateway` lo manda en el body (`tools[]`) y acumula los `delta.tool_calls`
   fragmentados del SSE; emite un `LlmChunk.ToolCall` por llamada, ya completa.
3. La UI la ve como `ToolCallCard` ("Ejecutando…") mientras corre la herramienta.

### Modo por prompt (LOCAL, Phi-4-mini en llama-server)

El servidor local no entiende `tools[]`, así que el catálogo se inyecta en el system prompt con un
protocolo de bloque delimitado:

````
```tool_call
{"tool":"nombre","arguments":{...}}
```
````

`PromptToolParser` es incremental (line-buffered): recognise el bloque en cuanto llega y **lo
retira del texto visible** durante el streaming, de modo que el usuario nunca ve el JSON.

### Reparación y reintentos (el sanitizador)

La salida del modelo llega sucia. `ToolCallSanitizer` (puro, sin I/O ni estado) intenta, en orden:

1. Quitar fences y texto alrededor del bloque.
2. Recortar al objeto JSON externo (`{ … }`).
3. Parsear con JSON leniente (`ignoreUnknownKeys`, `isLenient`).
4. Validar la forma `{"tool"|"name", "arguments"}` (yenvolver `{"arguments":{…}}` si el nombre
   vino fuera).

Si todo falla devuelve `SanitizedToolCall.Invalid(reason)`, y **ese motivo vuelve al modelo como
feedback**: el bucle consume la ronda, lo reintenta y sigue.

### Reglas del bucle

| Regla | Comportamiento |
|-------|----------------|
| Nunca propagar errores transitorios | JSON roto, herramienta desconocida o fallo al ejecutar **consumen un intento** y generan feedback para el modelo; se devuelve lo acumulado. |
| La cancelación externa sí se propaga | El botón "Detener" corta la corrutina: `CancellationException` no se traga. |
| Topes | Máx. 8 rondas modelo+herramienta; 2 reintentos de sanitización; 3 fallos de herramienta consecutivos. |
| Nunca perder el texto | Si el bucle se agota, se devuelve el texto visible hasta ese punto. |

### RAG + herramientas

`RagEngine.prepare()` separa la construcción del contexto (system prompt + chunks recuperados +
historial) de la ejecución. El `AgentLoop` reutiliza esos mensajes y agrega sus rondas. El modo
DOCS sigue exigiendo fuentes: una respuesta sin citas no se entrega como si estuviera respaldada.

### Verificación de citas (F9)

El system prompt dice "respondé sólo con el contexto", pero eso es una instrucción blanda: un
modelo chico puede parafrasear, exagerar o inventar. `rag.engine.CitationVerifier` lo convierte
en una comprobación programática y **determinista** (sin modelo, sin red):

| Criterio | Qué exige | Por qué |
|----------|-----------|---------|
| Literal | El fragmento citado aparece en la fuente | El caso normal. |
| Sin detalles inventados | Los nombres propios y los números (2+ dígitos) del fragmento aparecen en la fuente | Separa la paráfrasis de la invención ("DirectX 12" vs "Vulkan"). |
| Paráfrasis | ≥ 50% de las palabras significativas del fragmento aparecen en la fuente | Acepta parafrasis honestas; admite plurales y variaciones de verbo. |

`RagEngine.verify(respuesta, fuentes)` devuelve un informe por cita (`CitationCheck`: fragmento,
fuente, respaldada o no y por qué) y un resumen (`CitationReport`: válidas, desconocidas, fuentes
sin usar). La UI lo llama al terminar cada respuesta en modo Docs y muestra el distintivo
("Citas verificadas (n)" o cuántas quedan sin respaldo). El resultado se guarda con el mensaje
(`StoredCitation`) para que siga visible al reabrir el chat.

---

## Render de markdown y código (F2)

`app.ui` tiene su propio render, sin dependencias externas:

- **`Markdown.kt`**: `parseMarkdown(src)` devuelve un árbol de `MdNode` (`Heading`, `Paragraph`,
  `Bullets`, `Code`, `Quote`, `Table`, `Divider`) y `MarkdownText` lo pinta con `AnnotatedString`.
  Los estilos en línea (`**negrita**`, `` `código` ``, `[enlace](url)`) se resuelven en
  `buildInlineAnnotated`.
- **`CodeBlock.kt`**: bloque de código con su propio tokenizador y resaltado por lenguaje, botón
  de **copiar** y **guardar como…** con la extensión real del lenguaje (`.py`, `.kt`, `.cpp`,
  `.glsl`, `.html`, `.css`, `.js`, `.json`, `.rs`…) y diálogo `JFileChooser` en el EDT de Swing.
- En `ChatPane`, las respuestas del modelo se renderizan con `MarkdownText`; los mensajes del
  usuario siguen siendo texto plano.

---

## Permisos de herramientas (F3)

### Regla

El permiso es **por categoría**, está **apagado por defecto** y se concede **por chat**. Cada
herramienta declara su `ToolCategory` (`NONE`, `FILES`, `WEB`, `MCP`); `NONE` significa "sin
efecto externo" (la hora, por ejemplo) y nunca pregunta nada.

### La puerta única

`rag.agent.ToolPermissions.authorize(tool, argsJson, ctx)` es el **único** lugar donde se decide si
una herramienta corre:

```text
categoría NONE              -> ALLOW_ONCE (pasa)
categoría ya concedida      -> ALLOW_ONCE (pasa)
categoría no concedida      -> ctx.requestPermission(PermissionRequest(...))
                                -> ALLOW_ONCE | ALLOW_ALWAYS | DENY
```

`ToolContext` lleva lo que la puerta necesita: `chatId`, `outputsDir`,
`grantedCategories` y el callback `requestPermission`. `PermissionRequest` lleva la herramienta, la
categoría, un resumen legible (`Tool.describeCall(args)`) y los argumentos crudos, que la UI
muestra para que la decisión sea informada.

### Del backend a la UI

El bucle corre en una corrutina de I/O y la UI vive en el hilo de Swing, así que el diálogo se
resuelve con un `CompletableDeferred`:

```text
AgentLoop (hilo I/O)  --authorize()-->  ToolContext.requestPermission
        |                                        |
        |                          app.chat.askPermission(req)
        |                          (salta a Dispatchers.Main y espera)
        v                                        v
   PermissionDecision            PermissionDialog (AlertDialog)
   (vuelve por el deferred)      -> answerPermission(decision)
```

"Permitir siempre" además concede la categoría en ese chat (`grantCategory`), que persiste el
estado. Si la generación se cancela con el diálogo abierto, `askPermission` limpia el estado en
`NonCancellable` y la cancelación se propaga: nunca queda un diálogo colgado.

### Denegación

| Situación | Comportamiento |
|-----------|----------------|
| Primera denegación | El modelo recibe el feedback ("el usuario NO autorizó la herramienta, no la vuelvas a pedir") y sigue conversando. **No** cuenta como error de herramienta. |
| El modelo insiste | La segunda llamada se deniega **automáticamente** (no se repite el diálogo) y el bucle corta. |

### Estado por chat

El estado vive en el nodo del chat: `StoredNode.grantedCats` en `config/chats.json` (y
`ChatNode.Chat.grantedCats` en memoria). Los chats viejos, sin el campo, arrancan sin permisos
concedidos. Comandos: `/permitir-archivos`, `/permitir-web`, `/permitir-mcp`, `/revocar-*` y
`/permisos`. La cabecera del chat muestra **"Permisos: …"**.

---

## Herramientas de archivos y adjuntos (F3)

### Dónde se escriben

`create_file` y `download_url` escriben **siempre** dentro de
`app-home/outputs/<chat-id>/`. El modelo decide el **nombre**, nunca la carpeta: el nombre se
valida (sin separadores, sin `..`, no empieza con punto, extensión en lista blanca) y la ruta final
se normaliza y se comprueba contra el directorio.

| Herramienta | Qué hace | Topes |
|-------------|----------|-------|
| `create_file` | Escribe un archivo de texto o código (`.py`, `.kt`, `.cpp`, `.glsl`, `.html`, `.css`, `.js`, `.json`, `.rs`…) | 5 MB, nombre ≤ 120 caracteres |
| `download_url` | Descarga una URL `http/https` a la carpeta del chat | 50 MB, timeout de 5 min, `Content-Length` comprobado |

`download_url` escribe primero en `<archivo>.part` y mueve al final (atómico), con un `Mutex` por
ruta para que dos descargas al mismo archivo no se pisen. El nombre sale del `Content-Disposition`
o de la URL; si falta la extensión, se deduce del `Content-Type`. La red usa el `HttpClient` del
JDK, igual que `rag/retrieval/Sources.kt` (el backend no depende de Ktor).

### Del archivo a la burbuja

```text
ToolResult.Ok(artifacts = [Artifact])
    -> AgentEvent.ToolEnd(... artifacts)
    -> ExecutedTool(... artifacts)
    -> UiMessage.attachments (UiAttachment)
    -> StoredMessage.attachments (StoredAttachment)  [config/chats.json]
    -> AttachmentCard: Abrir (Desktop) | Guardar (JFileChooser, hilo de Swing)
```

`AttachmentCard` muestra ícono, nombre, tamaño y tipo MIME; **Abrir** lo lanza con la aplicación
del sistema y **Guardar…** copia el archivo donde elija el usuario.

---

## Herramientas web (F4)

Las dos herramientas web son de la categoría `WEB`: piden permiso como las de
archivos (apagado por defecto, se concede por chat con `/permitir-web`).

| Herramienta | Qué hace | Topes |
|-------------|----------|-------|
| `web_search(query)` | Busca en la web y devuelve hasta 8 resultados (título, URL, fragmento, motor) | 3 intentos, timeout 20 s |
| `web_fetch(url)` | Trae una página y la devuelve como texto legible (título, URL final, texto) | 2 MB, timeout 20 s |

### Motores de búsqueda

`web_search` no habla con ningún motor concreto: usa el contrato `SearchProvider`
(`backend/rag/agent/tools/WebTools.kt`), así que agregar otro motor es escribir
una clase más.

| Implementación | Motor | Rol |
|----------------|-------|-----|
| `DuckDuckGoProvider` | `html.duckduckgo.com` + API de instant answer | Principal, sin clave |
| `WikipediaProvider` | API de Wikipedia (`es` y `en`) | Respaldo, sin clave |
| `ChainSearchProvider` | — | Encadena: gana el primero que responda |

DuckDuckGo no publica una API sin clave y su HTML tiene un control anti-bot: a
veces contesta con una página de verificación (HTTP 202) en lugar de
resultados. El proveedor lo sortea en tres pasos:

1. **Preparar** el cliente: una visita a la portada (una sola vez) y un cookie
   jar en memoria, para que el servidor vea una sesión normal.
2. **Reintentar**: si llega la verificación, espera 800 ms y vuelve a pedir
   (hasta 3 intentos). La verificación es intermitente.
3. **Respaldar**: si aun así no hay nada, la API de instant answer (JSON, mucho
   más permisiva).

Si tampoco eso sirve, la cadena pasa a Wikipedia: la herramienta **siempre**
responde y cada resultado lleva su `source`, para que el modelo sepa de qué
motor salió. Los anuncios patrocinados (`duckduckgo.com/y.js`) se descartan.

### De HTML a texto

`web_fetch` no usa un parser de navegador: saca `script`, `style`, `noscript`,
`svg` y `head`, convierte los bloques en saltos de línea, resuelve las
entidades HTML (Latin-1, puntuación, símbolos y griegas, más las numéricas) y
recorta. La decodificación de entidades es de **una sola pasada**: `&amp;lt;`
queda `&lt;`, no `<`.

Si la respuesta es un **binario** (PDF, imagen), la herramienta no devuelve
basura: avisa que use `download_url`, que lo baja y lo deja como adjunto.

---

## Matemática y LaTeX (F5)

F5 tiene dos partes independientes: dibujar la matemática en el chat y
convertir un documento LaTeX en PDF.

### F5a — La matemática se dibuja (frontend)

`app.ui.MathRender` (`frontend/app/ui/MathRender.kt`) renderiza una fórmula
TeX a un `BufferedImage` **en memoria** con **JLaTeXMath 1.0.7** (Java puro:
no hace falta TeX instalado, ni red, ni escribir a disco). La imagen sale en
negro y se recolorea al `onSurface` del tema, para que se lea en claro y en
oscuro.

El flujo dentro del render de markdown es:

```text
Markdown.kt  parseMarkdown(src)
    -> MdNode.Math(latex, display)        (nodo nuevo, F5)
    -> buildAnnotatedWithMath()            (inline: appendInlineContent + Placeholder)
    -> MathRender.render(latex, display)   (imagen)
    -> ImageBitmap / DrawScope
```

`MathRender.splitMath` decide qué es fórmula y qué es texto. Las reglas
existen para no romper la prosa ni el streaming:

| Entrada | Resultado |
|---------|-----------|
| `$x^2$` | fórmula inline |
| `$$x^2$$` | fórmula de display |
| `$5 y $10` | texto (el `$` de cierre no puede ir seguido de dígito) |
| `` `$x$` `` | texto (el código en línea no es matemática) |
| `$x + y` (sin cerrar) | texto (seguro con streaming) |

### F5b — `latex_compile`: LaTeX → PDF (backend)

`rag.agent.tools.LatexCompileTool` (`backend/rag/agent/tools/LatexTool.kt`) es
de la categoría `FILES`: pide permiso como `create_file`.

```text
document + name? + engine?
    -> valida (documento vacío, tope de 1 MB, nombre saneado)
    -> busca el motor en el PATH: pdflatex, xelatex
         (sin motor -> ERROR con guía de instalación: fail-fast)
    -> escribe <nombre>.tex en outputs/<chat-id>/
         (si el fragmento no trae \documentclass, se le pone preámbulo)
    -> compila en un directorio temporal (los .aux y .log no ensucian outputs)
    -> copia el PDF a outputs/<chat-id>/<nombre>.pdf
    -> ToolResult.Ok(artifacts = [PDF, TEX])  -> AttachmentCard
```

| Detalle | Decisión |
|---------|----------|
| Motor | `pdflatex` primero, `xelatex` después; el modelo puede pedir uno con `engine` |
| Sin TeX instalado | Error con guía (TeX Live / MiKTeX) — nunca un PDF vacío |
| Timeout | 60 s de compilación |
| Tope | 1 MB de documento, 60 caracteres de nombre |
| Salida | Dos adjuntos: el PDF y el `.tex` (el usuario puede corregir y recompilar) |

El motor se ejecuta con `-interaction=nonstopmode -halt-on-error
-file-line-error`, y su salida se lee en un hilo aparte para no llenar el
buffer del proceso. Si la compilación falla, del log se extraen las líneas de
error de LaTeX (las `!` y las `archivo:línea:`) con la línea del `.tex` donde
estaba el problema: ese error vuelve al modelo, que puede corregir y volver a
intentar.

---

## MCP: herramientas de servidores externos (F7)

La app puede hablar con servidores MCP (Model Context Protocol) para usar
herramientas que no están implementadas en la app. **En esta ronda está
preparado el contrato, no el transporte**: falta el SDK oficial de Kotlin, así
que los servidores se declaran pero no se conectan, y la app lo dice con
todas las letras.

### El contrato (`rag.mcp`)

```kotlin
interface McpConnection {
    val serverId: String
    val displayName: String
    suspend fun listTools(): List<McpToolDescriptor>
    suspend fun callTool(name: String, args: JsonObject): McpToolResult
    suspend fun close()
}
```

`McpToolDescriptor` (nombre, descripción, JSON Schema), `McpToolResult`
(`Ok` con contenido / `Error`) y `McpContent` (`Text` o `Binary` en base64).

### La tool remota es una tool

`McpToolAdapter` implementa `Tool`, así que el resto de la app **no distingue
el origen**:

| Detalle | Decisión |
|---------|----------|
| Nombre | Calificado: `mcp__<servidor>__<herramienta>` (dos servidores pueden exponer tools homónimas) |
| Categoría | `MCP` → pide permiso (`/permitir-mcp`), apagado por defecto y por chat |
| Origen | `ToolOrigin.MCP` (para listarlas por separado; no cambia la ejecución) |
| Binarios | Se guardan en la carpeta del chat y aparecen como adjuntos |
| Errores | Del transporte o del servidor vuelven como `ToolResult.Error`: el modelo los ve y puede reintentar |

### El seam del transporte

`McpManager` pide conexiones al `McpConnector` y arma los adaptadores:

```
McpManager.refresh()
    -> por cada servidor declarado (y habilitado):
         connector.connect(server)   <-- EL seam: aca va el SDK oficial
         connection.listTools()     -> adaptadores
    -> estado por servidor (conectado / no conectado / por qué)
```

`PendingMcpConnector` es el conector por defecto: responde "falta el cliente
oficial". Un servidor caído no impide que los demás funcionen. Cuando llegue
el SDK, **sólo cambia el conector**: el `ChatViewModel` es el único lugar que
lo crea.

### Dónde se ve

- `/mcp` — estado de los servidores y cuántas tools MCP hay registradas.
- **Ajustes → Herramientas** — declarar, editar, habilitar y quitar servidores.
- La sección `mcp` de `app-config.json`.

---

## Flujo de Datos Principal

```
Usuario escribe -> ChatViewModel.send()
    |- Si engine listo -> AgentLoop (stream) -> UI actualiza token a token
    |- Si engine NO listo -> encola en pendingMessages + muestra "..." en UI
                          |
              boot() en background (corrutina) — por fases con % en UiProgress
    |- Fase 1: KnowledgeBase.sync() -> indexa docs (globales + del motor activo)
    |- Fase 2: descarga modelo (%, MB/MB)
    |- Fase 3: clonar/compilar llama.cpp si falta (%, sólo LOCAL y sólo si no está)
    |- Fase 4: LlmService.start() -> LocalServerManager.start() -> llama-server en puerto libre
    |- RagEngine creado -> ready = true -> processPendingMessages()
```

---

## Estado y organización del frontend

`ChatViewModel` es el dueño del estado: expone `MutableState` que Compose observa. Para que el
archivo no crezca, **las operaciones se reparten en archivos siblings como funciones de extensión
sobre el ViewModel** (Kotlin no tiene `partial class`):

| Archivo | Área | Contenido |
|---------|------|-----------|
| `ChatViewModel.kt` | núcleo | Estado, tema y perfiles de color, flujo del chat (envío, cola, cancelación, regeneración, edición) |
| `ChatLibrary.kt` | biblioteca | Árbol de chats y carpetas (crear, renombrar, mover, borrar) y persistencia en `config/chats.json` |
| `ChatEngine.kt` | motor | Arranque en segundo plano, reinicio del gateway, documentación por motor |
| `ChatCatalog.kt` | catálogo | Modelos GGUF (descarga, búsqueda en Hugging Face, activación) y APIs remotas (alta, prueba de conexión) |
| `ChatCommands.kt` | comandos | Comandos de barra (`/help`, `/model`, `/thinking`, `/power`, `/tools`, `/permitir-*`, `/revocar-*`, `/permisos`) |
| `ChatMessages.kt` | mapeo | Conversión entre `UiMessage` y `StoredMessage` (incluidas las tarjetas de herramientas y los adjuntos) |
| `ChatPermissions.kt` | permisos | Estado de permisos por chat y handshake `askPermission` / `answerPermission` con el backend |
| `PermissionDialog.kt` | permisos | Diálogo "¿Permitir?" (Permitir siempre / Una vez / Cancelar) |
| `AttachmentCard.kt` | adjuntos | Tarjeta de archivo producido por el modelo (Abrir / Guardar…) |

Los miembros que hacen falta desde varias áreas son `internal` (visibles dentro del módulo del
frontend, no fuera). Las pantallas que usan una extensión la importan explícitamente.

### Estado observable (extracto)

```kotlin
var screen by mutableStateOf(Screen.CHAT)          // Pantalla actual
var messages by mutableStateOf(listOf<UiMessage>()) // Historial del chat
var ready by mutableStateOf(false)                 // Modelo listo?
var busy by mutableStateOf(false)                  // Generando respuesta?
var queued by mutableStateOf(false)                // Hay mensajes en cola
var progress by mutableStateOf<UiProgress?>(null)  // Progreso de descarga/arranque
var status by mutableStateOf("")                   // Texto de estado
var error by mutableStateOf("")                    // Error visible (banner)
```

Reactividad: Compose se suscribe automáticamente a estos `MutableState`; los cambios producen
recomposición granular.

---

## Hilos y Concurrencia

| Componente | Dispatcher / Thread | Responsabilidad |
|------------|---------------------|-----------------|
| `ChatViewModel.scope` | Dispatchers.Main (Swing) | Estado UI, corrutinas de vida larga |
| `KnowledgeBase.sync` | Dispatchers.IO | I/O disco + red (GitHub) |
| `HuggingFaceDownloader` | Dispatchers.IO | Streaming HTTP chunks -> archivo |
| `LlamaManager` | Dispatchers.IO | git clone, cmake build, procesos |
| `LocalServerManager` | Dispatchers.IO | ProcessBuilder, health polling |
| `RagEngine.prepare` / `AgentLoop` | Dispatchers.IO | HTTP streaming al gateway + ejecución de herramientas |
| `ChatViewModel.testApi` | Dispatchers.IO + withTimeout(20 s) | GET /models de prueba ("Probar conexión") |
| `JFileChooser` | EDT de Swing | Diálogo "Guardar como…" / "Agregar archivos…" |
| UI updates | Dispatchers.Main | MutableState -> recomposición Compose |

Regla: todo I/O bloqueante va a `Dispatchers.IO`. La UI nunca se bloquea — `boot()` lanza en
segundo plano y el chat es usable de inmediato.

---

## Configuración Persistida

Archivo: `<app-home>/config/app-config.json`

```json
{
  "llm": {
    "configVersion": 4,
    "mode": "LOCAL",
    "hfRepo": "unsloth/Phi-4-mini-instruct-GGUF",
    "hfFile": "Phi-4-mini-instruct-Q4_K_M.gguf",
    "buildFromSource": true,
    "modelCatalog": [],
    "apiPresets": [],
    "uiDark": false,
    "uiAccent": -10401042
  },
  "rag": {
    "githubSources": [],
    "internalDocsDirs": ["internal-docs"],
    "chunkSize": 1200,
    "topK": 5
  },
  "tools": { "enabled": true, "timeoutMs": 60000 },
  "outputs": { "dir": "outputs", "maxFileMb": 5, "maxDownloadMb": 50 },
  "latex": { "engine": "", "timeoutMs": 60000, "maxDocumentKb": 1024 },
  "mcp": { "servers": [] }
}
```

- Carga: `AppConfig.loadOrCreate()` (crea defaults + system prompt + carpeta docs si es la
  primera vez).
- Persistencia: `config.save()` atómica (JSON pretty-print).
- **Migración por `configVersion`**: la versión actual es **4**. v1→v2: `buildFromSource` pasa a
  `true` y se eliminan las APIs localhost prefijadas. v2→v3: `chatTimeoutMs = 0` (sin límite de
  tiempo por respuesta). v3→v4: se agregan las secciones `tools`, `outputs`, `latex` y `mcp`,
  **con los mismos valores que usaban las constantes del código**: una instalación migrada se
  comporta exactamente igual que antes.
- Las secciones `tools`, `outputs` y `latex` **no son decorativas**: los lee el código (topes de
  tamaño, motor de LaTeX, timeout por herramienta) y se editan en **Ajustes → Herramientas**.
- La sección `mcp` declara los servidores MCP. El transporte todavía no está implementado: se
  declaran y quedan sin conectar (el estado lo muestra `/mcp`).

El árbol de chats vive aparte, en `<app-home>/config/chats.json` (esquema recursivo
`StoredNode`: carpetas con hijos y chats con mensajes; las tarjetas de herramientas se guardan en
`StoredMessage.toolCards`, los adjuntos en `StoredMessage.attachments` y los permisos concedidos
en `StoredNode.grantedCats`). Los chats guardados antes de estas features se siguen leyendo: los
campos ausentes toman su valor por defecto (sin tarjetas, sin adjuntos, sin permisos).

---

## Manejo de Errores

```kotlin
sealed class LlmErrorCode {
    object NO_CONFIGURADO : LlmErrorCode()      // Config invalida, binario faltante
    object DESCARGA_FALLIDA : LlmErrorCode()    // Red, integridad, 404
    object SERVIDOR_CAIDO : LlmErrorCode()      // Puerto ocupado, proceso muerto
    object TIMEOUT : LlmErrorCode()             // Health check, build, descarga
    object CANCELADO : LlmErrorCode()           // Usuario cancelo
}

data class LlmException(
    val code: LlmErrorCode,
    val message: String,
    val retryable: Boolean,
    val cause: Throwable? = null
)
```

- `retryable = true` -> botón "Reintentar" en la UI.
- `retryable = false` -> requiere acción del usuario (config, instalar git/cmake).
- Logs: `LocalServerManager` guarda el tail (últimos 40 KB) en `llama-server.log` y lo adjunta a
  las excepciones.
- Los errores del modelo en tool calling **no** son excepciones: son feedback (`Invalid.reason`)
  que vuelve al modelo para que reintente.

---

## Pantallas y Componentes (Frontend)

| Archivo | Responsabilidad |
|---------|-----------------|
| `app/Main.kt` | Entry point, ventana, tema dinámico |
| `app/NavigationRail.kt` | Sidebar colapsable (200/72dp), animado; "Chat / Modelos / Motor / Config" |
| `app/chat/ChatScreen.kt` | Layout raíz: sidebar + contenido + banner de carga |
| `app/chat/ChatPane.kt` | UI del chat: lista de burbujas, sidebar de chats y redactor |
| `app/chat/ChatSidebar.kt` | Árbol de chats/carpetas (drag & drop, click derecho) |
| `app/chat/MessageBubble.kt` | Burbuja de mensaje (usuario / modelo) con copiar y reiniciar |
| `app/chat/ToolCallCard.kt` | Tarjeta de herramienta: nombre, argumentos, estado y resultado |
| `app/chat/AttachmentCard.kt` | Tarjeta de adjunto: nombre, tamaño, tipo y acciones Abrir / Guardar… |
| `app/chat/PermissionDialog.kt` | Diálogo de permiso de herramienta (Permitir siempre / Una vez / Cancelar) |
| `app/chat/ComposerBar.kt` | Redactor, cola de mensajes y botones enviar/detener |
| `app/chat/ChatScrollbarAdapter.kt` | Scrollbar de altura estable (no salta con respuestas largas) |
| `app/chat/LoadingPane.kt` | Pantalla de carga completa (fallback + reintentar) |
| `app/screens/ModelsScreen.kt` | Catálogo de modelos: descargar/activar, agregar repo de HF |
| `app/screens/ApisScreen.kt` | Motor: LOCAL/REMOTE, colección de APIs, prueba de conexión, docs por API |
| `app/screens/SettingsPane.kt` | Tema claro/oscuro, color de acento |
| `app/screens/SettingsComponents.kt` | `themeOption`, `RadioGroup` reutilizables |
| `app/ui/Markdown.kt`, `app/ui/CodeBlock.kt` | Render de markdown y de bloques de código |

Los paneles largos (Motor, Config, Modelos) usan `verticalScroll` + `VerticalScrollbar`
(`rememberScrollbarAdapter`, no `LazyColumn`) para conservar el scroll del mouse.

---

## Extensibilidad

1. **Nueva herramienta**: implementa `Tool` en `rag.agent`, declara su `ToolCategory` (`NONE` si no
   tiene efecto externo) y regístrala en `ToolRegistry` (el ViewModel la instancia en
   `ChatViewModel.tools`). Si devuelve archivos, agregalos como `Artifact` en `ToolResult.Ok` para
   que aparezcan como adjuntos.
2. **Nueva fuente RAG**: implementa `DocSource` y agrégala a `KnowledgeBase.sync()`.
3. **Nuevo proveedor LLM**: implementa `LlmGateway` y regístralo en `LlmService.start()`.
4. **Nueva pantalla**: añade el valor al enum `Screen`, el `case` en `ChatScreen` y el item en
   `NavigationRail`.
5. **Config extra**: extiende `LlmConfig` / `RagConfig` (serialización automática con
   `@Serializable`).

---

## Requisitos de Ejecución

- JDK 21 (Eclipse Temurin recomendado)
- Git en PATH (para clonar llama.cpp)
- CMake 3.20+ (sólo si `llm.buildFromSource = true`)
- API compatible con OpenAI (opcional, para modo REMOTE)
- Windows/Linux/macOS — Compose Desktop genera instaladores nativos
- **TeX Live o MiKTeX (opcional, sólo para `latex_compile`)**: sin él, la
  matemática del chat funciona igual (JLaTeXMath) y la tool devuelve un error
  con la guía de instalación.

---

## Directorio de Trabajo (app-home)

```
~/.docs-chatbot/  (o $DOCS_CHATBOT_HOME o -Dapp.home=...)
├── config/
│   ├── app-config.json      # configuración persistida
│   ├── chats.json           # árbol de chats (carpetas + mensajes + tool cards)
│   └── system_prompt.md     # prompt del sistema (editable)
├── models/                  # GGUF descargados
├── llama.cpp/               # clon + build (si buildFromSource)
├── api-docs/<id>/           # documentación por motor (API o "local")
├── logs/
│   ├── llama-server.log
│   └── llama-build.log
├── data/cache/              # repos GitHub clonados para indexación
└── internal-docs/           # docs locales del usuario (.md, .txt, .rst, .adoc)
```
