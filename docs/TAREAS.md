# Lista de tareas - FunshiEngineAI

Lista de pendientes y mejoras planificadas. Cada tarea se marca al implementarse
y se mueve a `docs/CHANGES.md` en el próximo registro de versión.

---

## Pendientes

### 0. Integración por fases: herramientas, formatos, web, archivos, LaTeX, HF Spaces, MCP

**Estado:** PLANIFICADO (2026-10-19) — diseño completo en `docs/PLAN-INTEGRACION.md`
(decisiones cerradas con el usuario; ver §2 de ese documento). **F1, F2, F3, F4,
F5, F7, F8 y F9 IMPLEMENTADOS (2026-10-20), compilados y verificados. F6
(congelada) y el transporte http de MCP pendientes.**

Implementación por fases (orden acordado):

- [x] **F1**: `LlmChunk` tipado + `tools` en gateway OpenAI-compatible +
      `AgentLoop` + `ToolRegistry` + `ToolCallCard` + tool de prueba `get_time`.
      Tool-calling híbrido: nativo en REMOTE, JSON delimitado por prompt en LOCAL.
- [x] **F2**: Renderer markdown propio (`app/ui/Markdown.kt`: parser `MdNode` +
      `MarkdownText`) + `CodeBlock` con syntax highlight propio, copiar y
      "Guardar como…" con extensiones reales (`.py`, `.kt`, `.cpp`, `.glsl`,
      `.html`, `.css`, `.js`, `.json`, `.rs`…).
- [x] **F3**: `create_file` + `download_url` + `AttachmentCard` + permisos FILES
      + comando `/permitir-archivos` (permisos por categoría, default off,
      asociados al chat activo). **Implementado (2026-10-20)**: `ToolCategory`
      (`NONE`/`FILES`/`WEB`/`MCP`), `ToolPermissions.authorize()` como única
      puerta, `ToolContext` con `outputsDir` + `grantedCategories` + callback de
      permiso, `PermissionRequest`/`PermissionDecision`, `Artifact` en
      `ToolResult.Ok`, `create_file` (5 MB, extensión en lista blanca, sin
      rutas), `download_url` (50 MB, `.part` + move atómico, `Mutex` por ruta),
      diálogo de permiso, tarjeta de adjunto (Abrir/Guardar), comandos
      `/permitir-*` / `/revocar-*` / `/permisos` y etiqueta "Permisos: …" en la
      cabecera.
- [x] **F4**: `web_search` (DuckDuckGo, sin clave) + `web_fetch` + permisos WEB
      + `/permitir-web` + escenario E2E: pedir un PDF generado por una IA web y
      entregarlo como adjunto descargable. **Implementado (2026-10-20)**:
      `backend/rag/agent/tools/WebTools.kt` con el contrato `SearchProvider` y
      la cadena `DuckDuckGoProvider` (motor principal: prepara el cliente,
      reintenta si llega la página de verificación y cae a la API de instant
      answer) → `WikipediaProvider` (respaldo). `web_fetch` trae el HTML,
      detecta el charset, saca script/estilo, decodifica entidades y reconoce
      binarios (si es un PDF o una imagen pide usar `download_url`). Los dos son
      de categoría `WEB`: piden permiso (apagado por defecto) igual que los
      de archivos.
- [x] **F5**: Math LaTeX en chat (JLaTeXMath, offline) + `latex_compile` → PDF
      (autodetecta TinyTeX/MiKTeX; fail-fast con guía si falta).
      **Implementado (2026-10-20)**: F5a —
      `frontend/app/ui/MathRender.kt` (JLaTeXMath 1.0.7, render a `BufferedImage`
      en memoria, recoloreado al `onSurface` del tema) integrado en
      `frontend/app/ui/Markdown.kt` como `MdNode.Math`: `$…$` inline con
      `appendInlineContent` y `$$…$$` como bloque. El `$` de cierre no puede
      ir seguido de dígito (`$5 … $10` es texto), el `$` sin cerrar queda como
      texto (seguro con streaming) y el código en línea nunca es math. F5b —
      `backend/rag/agent/tools/LatexTool.kt` (tool `latex_compile`, categoría
      FILES): busca `pdflatex`/`xelatex` en el PATH, escribe el `.tex` en
      outputs (con preámbulo estándar si el modelo manda un fragmento),
      compila en un directorio temporal y devuelve el PDF y el `.tex` como
      adjuntos. **Fail-fast**: sin motor instalado devuelve un error con la
      guía (TeX Live / MiKTeX) en vez de un PDF vacío.
- [ ] **F6**: `GradioSpaceGateway` — modelo remoto vía HF Spaces. **CONGELADA**
      (2026-10-19): decisión pendiente, el usuario la dejó incompleta. Ver §11
      del plan (alternativa recomendada: HF Inference Providers).
- [x] **F7**: Contrato MCP (`McpConnection` + registro con origen LOCAL|MCP,
      sin SDK aún — sin servidores que conectar) + config v4 + pantalla de
      permisos + documentación (`ARCHITECTURE.md`, `CHANGES.md`).
      **Implementado (2026-10-20)**: `backend/rag/mcp/McpToolAdapter.kt` con los
      contratos (`McpConnection`, `McpToolDescriptor`, `McpToolResult`,
      `McpContent`) y el adaptador que convierte una tool remota en una tool de
      la app (nombre calificado `mcp__servidor__tool`, categoría MCP, origen
      MCP, binarios como adjuntos). `backend/rag/mcp/McpManager.kt` administra
      los servidores y expone su estado; `backend/rag/mcp/McpConfig.kt` define
      la sección `mcp`. **El transporte NO está implementado**: el conector por
      defecto lo dice con todas las letras y `/mcp` muestra el estado.
      `Tool` declara su origen (`ToolOrigin`) y `ToolRegistry` filtra y
      reemplaza tools por origen. Config **v4** con las secciones `tools`,
      `outputs`, `latex` y `mcp` (migración automática desde v3, con los
      mismos valores que las constantes de antes) y pantalla **Ajustes →
      Herramientas** para editarlas. Los permisos MCP ya funcionaban
      (`/permitir-mcp`, `/revocar-mcp`, `/permisos`).
- [x] **F8**: Cliente MCP **real por stdio** (JSON-RPC 2.0 propio, detrás del
      seam `McpConnector`, sin SDK oficial).
      **Implementado (2026-10-20)**:
      `backend/rag/mcp/McpProtocol.kt` (JSON-RPC puro: construir y leer
      mensajes) y `backend/rag/mcp/McpStdioClient.kt` (`McpProcess`/`JvmMcpProcess`,
      `StdioMcpConnection`, `McpStdioConnector`, `DefaultMcpConnector`).
      Ciclo: `initialize` → `notifications/initialized` → `tools/list` →
      `tools/call`. Los mensajes van en una línea (JSON compacto delimitado por
      `\n`); el parser ignora el ruido en stdout y responde `-32601` a las
      peticiones del servidor al cliente. stderr se drena en segundo plano
      (si no, el proceso se bloquea al loguear), timeout por petición
      (`mcp.timeoutMs`, con `notifications/cancelled`) y en Windows los
      `.cmd`/`.bat` se lanzan con `cmd.exe /c`. El transporte **http** sigue
      sin implementarse y la app lo dice. **Por qué no el SDK oficial**:
      `io.modelcontextprotocol:kotlin-sdk-client` exige Kotlin ≥2.2 y ktor
      ≥3.2.3 (la última, Kotlin 2.4 + ktor 3.5) y el proyecto está en Kotlin
      2.1.0 + ktor 3.0.3: adoptarlo sería subir el toolchain de todo el
      proyecto. Tests: `McpStdioClientTest` (doble en memoria) y
      `McpStdioProcessTest` (proceso real).
- [x] **F9**: Verificador determinista de citas (post-generación) + distintivo
      en el chat. **Implementado (2026-10-20)**:
      `backend/rag/engine/CitationVerifier.kt` (puro: literal, sin detalles
      inventados, paráfrasis), `StoredCitation` persistida y
      `CitationBadge` en la burbuja del mensaje.
- [x] **Infraestructura de tests**: JUnit 5 en los módulos,
      `rag.test.runTest { }` como helper y 367 tests deterministas
      (reglas en `docs/TESTING.md`).

---

### 0.1 Voz: dictado y chat por voz (V1)

**Estado:** IMPLEMENTADO (2026-10-21) — compilado, 376 tests en verde y app arrancada
sin crash. Diseño completo en `.agentes/plan-voz.md` (análisis, decisiones, tareas
P0–P15).

Qué se hizo:

- **Módulo nuevo `voice-api`** (hermano de `llm-api`), expuesto por `backend` con
  `api(project(":voice-api"))` y heredado por `frontend`. **No usa ktor**: el HTTP es
  el `HttpClient` del JDK, igual que en `rag/retrieval/Sources.kt`. Deps: sólo
  coroutines y kotlinx-serialization.
- **Dictado por voz**: el micrófono del redactor transcribe y **deja el texto en el
  redactor** (no se envía solo). Para eso el texto del redactor se movió del
  composable al ViewModel (`ChatViewModel.composerInput`).
- **Chat por voz**: bucle `escuchar → enviar → responder → hablar`. El texto
  transcrito entra por `ChatViewModel.send()`, o sea por **el mismo camino que el
  chat de texto**: RAG, herramientas, permisos, citas y persistencia funcionan
  igual. No hay un segundo chat.
- **Un solo seam** entre la voz y el chat: `ChatViewModel.voiceAnswer`
  (pregunta + `CompletableDeferred<String>`), completado en `sendInternal()` y en
  `handleCommand()`. La capa de voz no toca el bucle del agente.
- **Motores**: STT `OPENAI` (`POST /v1/audio/transcriptions`) y `WHISPER_CLI`
  (whisper.cpp local, el audio no sale de la máquina); TTS `OPENAI`
  (`POST /v1/audio/speech`) y `SYSTEM` (`powershell`/`say`/`espeak-ng`).
- **Audio**: PCM 16 bits mono little-endian en todos los contratos; downmix y
  remuestreo a 16 kHz con funciones **puras** (`AudioResampler`).
- **Fin de habla**: detector de silencio por **RMS** (`SilenceDetector`), con umbral
  y topes configurables. El micrófono se **cierra** mientras se reproduce la
  respuesta (ciclo secuencial: no se escucha a sí mismo).
- **Sin permisos de tool para la voz**: el micrófono lo enciende el usuario, no
  el modelo.
- **Config**: sección `voice` en `app-config.json`, `configVersion` **4 → 5**. La
  voz entra **apagada** (`enabled: false`), así que la migración es invisible.
  `AppConfig.resolvedVoice()` hereda URL y clave del motor de inferencia activo si
  quedan vacías.
- **UI**: botón de micrófono en `ComposerBar`, barra de voz (`VoiceBar`) con el
  botón de chat por voz, comandos `/voz`, `/dictar` y `/voz-off`, y **Ajustes → Voz**
  (`VoiceSettings`).
- **Errores tipados**: `VoiceException` + `VoiceErrorCode` con código y `retryable`,
  igual que `LlmException`. Sin motor configurado, la UI lo dice y no falla en
  silencio.
- **Ajustes que sí se repintan**: `ChatViewModel.config` es estado de Compose
  (`mutableStateOf`): sin eso, guardar un cambio no repintaba la pantalla y el
  interruptor de la voz "no se activaba".
- **Scrollbar del tema y con teclado**: `app.ui.ThemedVerticalScrollbar` (el
  de Compose es negro al 12% e invisible en el tema oscuro) y, con `Tab`, las
  flechas **↑ ↓** mueven el panel sin mouse.

Criterios de aceptación:

- [x] El micrófono del redactor transcribe la voz y deja el texto en el redactor.
- [x] El chat por voz usa el mismo camino que el chat de texto (RAG, herramientas,
      permisos, citas y persistencia).
- [x] La respuesta se lee en voz alta y el ciclo vuelve a escuchar.
- [x] Ningún dato de audio sale de la máquina si el motor STT es local.
- [x] Sin motor configurado, la UI lo dice y no falla en silencio.
- [x] Error tipado (`VoiceException`) con código y `retryable`.
- [x] Config `voice` apagada por defecto; la migración v4 → v5 no cambia el
      comportamiento de una instalación existente.
- [x] Tests deterministas: sin red, sin micrófono, sin esperas.

Pendiente (fuera de V1, ideas):

- [ ] Un **tool `speak`** que el modelo pueda pedir (hoy la voz la enciende el
      usuario).
- [ ] **Voz por streaming** del LLM: leer la respuesta mientras se genera en vez
      de esperar a que termine.
- [ ] **Hotwords / vocabulario del motor** en la transcripción (nombres propios del
      proyecto).

---

### 1. Modelos: "Ver archivos" inline bajo cada resultado (con "Ocultar archivos")

**Estado:** IMPLEMENTADO (2026-10-18) — compilado.

Implementación:

- Estado por resultado: `expandedRepos: Set<String>` (varios expandidos a la vez),
  `files: Map<String, List<HfGgufFile>>` y `listing: Set<String>` (carga por repo,
  sin pisarse).
- `pickRepo(id)` ahora es toggle: colapsa si está abierto; si no, lo abre, marca
  `repo = id` y le pide los `.gguf` sólo si faltan (no re-pide al recolapsar).
- Composable `RepoFilesList(files, loading, selectedFile, onSelect)` renderizado
  **dentro** de `results.forEach`, envuelto en `AnimatedVisibility` con la fila;
  etiqueta alterna "Ver archivos" / "Ocultar archivos".
- El ✔ sólo se pinta dentro del repo elegido (`repo == hit.id`).
- "Repo elegido" abajo queda como resumen (repo + archivo), sin duplicar la lista.
- `Modifier.animateContentSize()` para que el despliegue no salte.

**Problema actual**

- El listado de `.gguf` se renderiza una sola vez, en el bloque
  `if (repo.isNotBlank())` que queda *después* del `results.forEach` (línea ~116),
  o sea "abajo del todo" de la tarjeta del buscador, lejos del resultado que se
  clickeó.
- El botón siempre dice "Ver archivos": no hay estado de expandido/oculto, así
  que no se puede cerrar la lista sin elegir otro repo.
- `files` / `listing` son estado único del pane (no por repo), pensados para un
  solo repo abierto a la vez.

**Solución propuesta (acordeón por resultado, VARIOS abiertos a la vez)**

1. Estado nuevo: `var expandedRepos by remember { mutableStateOf(setOf<String>()) }`
   (varios resultados pueden estar abiertos en simultáneo).
2. `files` pasa de `List<HfGgufFile>` a `Map<String, List<HfGgufFile>>` (clave =
   id del repo) y `listing` a `Set<String>` (repos que se están listando), así
   cada resultado carga y muestra sus propios archivos sin pisar a los demás.
   `vm.listRepoFiles(id)` responde por callback: se guarda en el map de ese repo.
3. `pickRepo(id)` pasa a ser *toggle*:
   - si `id ∈ expandedRepos` → lo quita del set (colapsa);
   - si no → lo agrega al set, setea `repo = id`, limpia `file` si cambió de
     repo y dispara `vm.listRepoFiles(id)` sólo si ese repo aún no tiene
     archivos cargados (evita re-pedir al recolapsar).
4. Extraer el render de la lista de archivos a un composable privado
   `RepoFilesList(files, listing, selectedFile, onPickFile)` y llamarlo **dentro
   del `results.forEach { hit -> ... }`**, justo debajo de la fila, envuelto en
   `AnimatedVisibility(hit.id in expandedRepos)` para que el despliegue sea
   instantáneo y "dinámico".
5. Etiqueta condicional en el botón y en la fila clickeable:
   `Text(if (hit.id in expandedRepos) "Ocultar archivos" else "Ver archivos")`.
6. El botón superior "Listar .gguf del repo" (y el alta manual) también deben
   abrir el acordeón: `expandedRepos = expandedRepos + repo`.
7. Detalle de UX: como la expansión ocurre *debajo* de la fila clickeada, la
   fila no se mueve de lugar, por lo que no hace falta auto-scroll.

**Efectos/riesgos a tener en cuenta**

- `file` (selección con ✔) es global y sólo se limpia al cambiar `repo`: con
  varios acordeones abiertos el ✔ sólo debe pintarse dentro del repo elegido
  (comparar `repo == hit.id` antes de marcar).
- La selección de un archivo debe mantener el acordeón abierto para que se vea
  el ✔.
- La tarjeta del buscador crece en altura (`verticalScroll` del pane): usar
  `Modifier.animateContentSize()` en la Columna de la tarjeta para que no salte.

**Criterios de aceptación**

- [x] Al pulsar "Ver archivos" en un resultado, los `.gguf` aparecen justo debajo
      de esa fila.
- [x] El botón alterna a "Ocultar archivos" y la lista se cierra.
- [x] Varios resultados pueden estar expandidos a la vez, cada uno con sus
      propios archivos (sin pisarse).
- [x] El archivo elegido se ve marcado dentro de la lista inline del repo elegido.
- [x] "Repo elegido" abajo no duplica la lista.

---

### 2. Modo "Docs" híbrido

**Estado:** DESCARTADO por el usuario (2026-10-18) — "el chat me gusta como está".
Queda anotado por si en el futuro cambia la opinión. Idea original: en modo DOCS,
si el contexto de la documentación no cubre la pregunta, caer al conocimiento
general del modelo con una nota, en vez de responder "No encuentro eso en la
documentación".

---

### 3. Refactor: sistema de chats con carpetas (árbol, drag & drop, click derecho)

**Estado:** IMPLEMENTADO (2026-10-18) — compilado y en prueba en runtime.

Implementación:

- `estructuras/ArbolEnlazado`: nuevo método `move(p, newParent, index)` con
  validación de ciclos (no mover un nodo dentro de su propio subárbol).
- `backend/rag/ChatStore`: esquema recursivo `StoredNode` (carpeta con hijos /
  chat con mensajes) con **migración automática** del formato v1 plano.
- `frontend/app/ChatTree.kt`: `ChatNode` (Folder/Chat), `ChatRow` (fila
  aplanada) y `ChatTree` (árbol + índice id→posición + serialize/deserialize).
- `ChatViewModel`: `newChat(parentId)`, `newFolder(parentId)`, `renameNode`,
  `deleteNode` (recursivo), `moveNode`, `dropOnto`, `toggleFolder`,
  `subtreeCounts`; `chatRows`/`expandedIds` como estado de UI. El chat activo
  se salva del subárbol borrado; siempre queda al menos un chat.
- `ChatPane`: sidebar sin botón "+ Nuevo chat"; carpetas con ▸/▾ e indentación
  por profundidad; **click derecho** sobre items (renombrar/borrar/crear dentro)
  y sobre el fondo (crear en la raíz); **diálogo de renombrar**; **confirmación
  de borrado** con conteo de chats/carpetas; **drag & drop manual**
  (`detectDragGestures` + hit-testing de límites por fila, con fantasma que
  sigue al puntero) — la API `dragAndDropSource/Target` de Compose 1.7.3 no
  existe aún, se implementó a mano.
- Nota Compose 1.7.3: no hay `onPreviewPointerEvent`, ni `MimeType`, ni
  `PointerInputChange.button` (el botón está en `PointerEvent.button`).

Puntos ya definidos con el usuario:

- Usar las estructuras del módulo `estructuras` (`ArbolEnlazado<ChatNode>`) como
  modelo en memoria del árbol chat/carpeta.
- Carpetas anidables; un chat es siempre hijo de una carpeta (o de la raíz).
- Arrastrar chats y carpetas desde la GUI para ordenarlos y meterlos en carpetas.
- Carpetas desplegables (expand/collapse).
- Renombrar chats y carpetas.
- Menú de click derecho sobre la barra de chats: crear chat, crear carpeta,
  renombrar, borrar.
- Eliminar el botón "+ Nuevo chat".
- Persistencia anidada (nuevo esquema en `config/chats.json` con migración del
  formato plano viejo).

Decisiones de UX confirmadas (2026-10-18):

- **Borrar carpeta con contenido:** confirmación ("Se eliminarán N chats y
  M carpetas, ¿continuar?") y borrado recursivo del subárbol.
- **Click derecho:** funciona sobre items y sobre la zona vacía de la barra.
  Sobre chat → renombrar, borrar. Sobre carpeta → renombrar, borrar, crear
  chat/carpeta dentro. Sobre el fondo vacío → crear chat o carpeta en la raíz.
- **Orden:** primero terminar este refactor; la mejora "Ver archivos"
  (tarea 1) se implementa después.

Nota sobre persistencia de chats: **ya existe** (`ChatStore` guarda en
`config/chats.json` tras cada cambio). Este refactor la extiende al nuevo
esquema anidado; no hay que crear el sistema desde cero.

---

### 4. Motor de inferencia: botón "Documentación" por API

**Estado:** IMPLEMENTADO (2026-10-18) — compilado. Decisión: **documentación POR
MOTOR** (cada API y el modelo local tienen su propia carpeta).

Implementación:

- Carpeta por motor: `app-home/api-docs/<id>/` (`"local"` para el modelo local).
  Los archivos se **copian** ahí desde el selector del SO, así la persistencia no
  depende de rutas temporales; no hace falta campo en `ApiPreset`.
- `KnowledgeBase.motorDocsDir`: carpeta opcional indexada además de las globales
  (GitHub + `internal-docs`), con etiqueta de citación `motor/<id>`.
- `ChatViewModel`: `activeMotorId()`, `docsDirFor()`, `listDocs()` (nombre +
  extensión + peso), `addDocsFiles()` (copia sin pisar nombres: sufijo `-2`,
  `-3`…), `removeDocsFile()`; `boot()`/`reindex()` setean `kb.motorDocsDir`
  antes del `sync` → **al activar otra API se reindexa con sus docs**.
- Reindexado automático tras agregar/quitar sólo si el motor es el activo (los
  demás se indexan al activarse).
- UI (`ApisScreen.kt`): botón **"Documentación"** en cada tarjeta de API y en la
  tarjeta del modelo local. Abre `DocsPanel` (AlertDialog) con el listado
  (nombre completo, "Extensión: .md · 12 KB"), botón "Eliminar" por archivo y
  "Agregar archivos desde el sistema…" (JFileChooser en el hilo de Swing,
  filtro a formatos de texto: md/txt/rst/adoc/mdx — los únicos que el indexer
  de texto sabe leer; PDF/binarios quedan fuera a propósito).

Criterios de aceptación:

- [x] Botón "Documentación" en Motor de inferencia, por API y en local.
- [x] Panel con archivos cargados (nombre + extensión).
- [x] Agregar archivos desde el sistema (se copian al motor y se indexan).
- [x] Eliminar archivos (se borran y se reindexa si el motor está activo).
- [x] Persistencia al reinicio (carpeta propia en `app-home/api-docs/`).

---

### 5. Modularización de la arquitectura y reescritura de la documentación

**Estado:** IMPLEMENTADO (2026-10-20) — compilado y verificado en runtime.

Qué se hizo:

- **Paquetes por área** en los tres módulos (el mapa completo está en
  `docs/ARCHITECTURE.md`):
  - `llm-api`: `llm.api`, `llm.config`, `llm.error`, `llm.local`, `llm.catalog`,
    `llm.service`.
  - `backend`: `rag.config`, `rag.store`, `rag.retrieval`, `rag.engine`, `rag.agent`.
  - `frontend`: `app`, `app.core`, `app.ui`, `app.chat`, `app.screens`.
- **Archivos grandes partidos** en unidades con una responsabilidad cada una:
  - `ChatPane.kt` → `ChatPane` + `ChatSidebar` + `MessageBubble` + `ToolCallCard` +
    `ComposerBar`.
  - `ChatViewModel.kt` (1.158 líneas) → `ChatViewModel` (núcleo: estado, tema y
    flujo del chat) + `ChatLibrary` + `ChatEngine` + `ChatCatalog` + `ChatCommands` +
    `ChatMessages`. Como Kotlin no tiene `partial class`, las operaciones de cada
    área son **funciones de extensión sobre el ViewModel** (el estado sigue siendo
    del ViewModel; lo que se reparte es el código).
  - Members compartidos entre áreas pasan a `internal` (visibles sólo dentro del
    módulo del frontend).
- **Documentación reescrita**: `docs/ARCHITECTURE.md` (mapa de paquetes, tool
  calling, render markdown/código, concurrencia, config), `docs/CHANGES.md`
  (v0.6.0) y `README.md`.

Criterios de aceptación:

- [x] Los tres módulos compilan (`llm-api`, `backend`, `frontend`).
- [x] La app arranca y renderiza igual que antes del refactor.
- [x] Ningún archivo supera ~600 líneas y cada uno tiene una responsabilidad
      única.
- [x] La documentación refleja la estructura real del código.
