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
| `rag.config` | `AppConfig`, `AppPaths`, `RagConfig`, perfiles de color |
| `rag.store` | `ChatStore`: persistencia del árbol de chats (`config/chats.json`) |
| `rag.retrieval` | `Chunker`, `Bm25Index`, `KnowledgeBase`, `Sources`, modelos de documento |
| `rag.engine` | `RagEngine`: arma el prompt (system + contexto + historial) y responde |
| `rag.agent` | Tool calling: `Tool`, `ToolResult`, `ToolContext`, `ToolRegistry`, `AgentLoop`, `PromptToolParser`, `ToolCallSanitizer`, `ToolPermissions`, `tools/GetTimeTool`, `tools/FileTools` |

### `frontend` — UI

| Paquete | Contenido |
|---------|-----------|
| `app` | `Main` (ventana, tema), `NavigationRail` (sidebar colapsable) |
| `app.core` | Tipos compartidos: `UiState.kt` (`Screen`, `UiMessage`, `UiToolCall`, `UiProgress`), `ChatTree.kt` (`ChatNode`, `ChatRow`, `ChatTree`) |
| `app.ui` | Render de contenido: `Markdown.kt` (parser + `MarkdownText`), `CodeBlock.kt` (resaltado, copiar, guardar) |
| `app.chat` | La pantalla de chat: `ChatViewModel` + áreas, `ChatCommands`, `ChatMessages`, `ChatPane`, `ChatSidebar`, `MessageBubble`, `ToolCallCard`, `ComposerBar`, `ChatScreen`, `ChatScrollbarAdapter`, `LoadingPane` |
| `app.screens` | `ModelsScreen`, `ApisScreen`, `SettingsPane`, `SettingsComponents` |

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
    "configVersion": 3,
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
  }
}
```

- Carga: `AppConfig.loadOrCreate()` (crea defaults + system prompt + carpeta docs si es la
  primera vez).
- Persistencia: `config.save()` atómica (JSON pretty-print).
- **Migración por `configVersion`**: la versión actual es **3**. v1→v2: `buildFromSource` pasa a
  `true` y se eliminan las APIs localhost prefijadas. v2→v3: `chatTimeoutMs = 0` (sin límite de
  tiempo por respuesta).
- La v4 (permisos por categoría + MCP) llega con F7.

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
