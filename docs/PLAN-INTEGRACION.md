# Plan de Integración y Arquitectura — Herramientas, Formatos, Web, Archivos, LaTeX y MCP

Documento de plan (diseño). La implementación se sigue en `docs/TAREAS.md` y el
historial en `docs/CHANGES.md`.

---

## 1. Contexto y objetivo

FunshiEngineAI hoy es un chat RAG con streaming: el modelo emite **texto plano** que
la UI pinta con `Text(m.text)`. No hay tool calling, ni render de markdown/código,
ni acceso a red por parte del modelo, ni archivos descargables, ni math, ni MCP.

Objetivo: convertir el chat en un **asistente con herramientas**:

1. **Tool calling** (base transversal): el modelo puede pedir que la app ejecute
   acciones programáticas (buscar, leer web, crear archivos, bajar archivos).
2. **Visualización de scripts**: bloques de código con lenguaje/extensiones reales
   (`.py`, `.java`, `.cpp`, `.glsl`, `.html`, `.css`…), resaltado y exportación.
3. **Archivos**: crear archivos y entregarlos como adjuntos descargables en chat;
   descargar archivos desde URL (p. ej. un PDF devuelto por una IA web).
4. **Navegación web**: `web_search` (DuckDuckGo, sin clave) + `web_fetch` (lectura
   de páginas).
5. **LaTeX**: math inline/display + compilación de documentos LaTeX → PDF.
6. **MCP**: preparar la arquitectura (contratos + adaptador) para ensamblar el
   cliente MCP cuando haya servidores concretos.
7. **Permisos**: confirmación manual + comandos `/permitir-*` por categoría
   asociados al chat activo.

---

## 2. Decisiones cerradas con el usuario

| Tema | Decisión |
|------|----------|
| Tool-calling | **Híbrido por motor**: REMOTE OpenAI-compatible → nativo (`tool_calls` en SSE); LOCAL (llama-server) y HF Spaces Gradio → guiado por prompt (JSON delimitado extraído del stream). Un solo `AgentLoop` para todos. |
| Permisos | **Por categoría, default off**: `/permitir-web`, `/permitir-archivos` (y `/permitir-mcp` futuro) habilitan su categoría **sólo en el chat activo**; por defecto todo pide confirmación en UI. `/permisos` muestra el estado. |
| Búsqueda web | **DuckDuckGo** (API sin clave). Interfaz `SearchProvider` para agregar otros luego. |
| Modelo remoto | **HF: pendiente de decisión** (F6 congelada; ver `docs/PLAN-INTEGRACION.md` §10). |
| LaTeX | **Math en chat + documentos LaTeX → PDF** (requiere TinyTeX/MiKTeX externo; la app lo autodetecta y ofrece guía de instalación). |
| Formatos | Extensiones de archivo reales por lenguaje; exportar bloque/mensaje como archivo con su extensión. |
| MCP | Sólo **arquitectura preparada** ahora (contrato + adaptador + config); ensamblaje del SDK cuando existan servidores a conectar. |
| Orden | F1 → F7 (ver §8). |

---

## 3. Sección A — Núcleo: capa de Tool Calling

Es la base de web, archivos, LaTeX-docs y MCP. Va primero.

**Estado: IMPLEMENTADO (2026-10-20), compilado.** Lo de abajo es el diseño
original; la implementación sigue exactamente este contrato, con esta diferencia:
el `ToolSpec` y los `LlmChunk` viven en `llm-api` (el gateway los serializa y
backend depende de llm-api, al revés no).

### 3.1 Contrato de herramientas

```kotlin
// backend/agent/Tool.kt
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,   // JSON Schema de argumentos
)

interface Tool {
    val spec: ToolSpec
    /** Categoría de permiso que exige (NONE = siempre disponible). */
    val category: ToolCategory
    /** Resumen legible de la llamada (para el diálogo de permiso). */
    fun describeCall(args: JsonObject): String
    /** Ejecuta la herramienta y devuelve un resultado apto para devolver al modelo. */
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

sealed class ToolResult {
    /** El texto va al modelo; [artifacts] son los archivos producidos. */
    data class Ok(val text: String, val artifacts: List<Artifact> = emptyList()) : ToolResult()
    data class Error(val message: String) : ToolResult()    // error legible
}
```

`ToolContext` lleva: chat activo (`chatId`), carpeta de salidas
(`outputs/<chat-id>`), las categorías ya concedidas en ese chat
(`grantedCategories`) y el callback `requestPermission` con el que se pide
permiso al usuario. Todo el I/O bloqueante de las herramientas va en
`Dispatchers.IO`.

### 3.2 Cambios en `LlmGateway` (`llm-api`)

- `chatStream(messages, tools: List<ToolSpec>?): Flow<LlmChunk>` — el SSE pasa a
  emitir chunks tipados:

```kotlin
sealed class LlmChunk {
    data class Text(val delta: String) : LlmChunk()
    data class ToolCall(val id: String, val name: String, val argsJson: String) : LlmChunk()
}
```

- **Gateway OpenAI-compatible**: parseo de `delta.tool_calls[]` (acumulación de
  fragmentos de `arguments` por index), `finish_reason`, envío de `tools[]` en el
  body, mensajes `role=tool` con `tool_call_id`.
- **Gateway LOCAL (llama-server)**: no envía `tools` en el body; el catálogo va en
  el system prompt (inyectado por el AgentLoop) y emite un bloque delimitado
  ` ```tool_call ... ``` ` que se extrae del stream (parser incremental de
  delimitadores, robusto a streaming parcial). `chat()` devuelve el tool call como
  `LlmChunk.ToolCall`.
- **`GradioSpaceGateway`** (nuevo): adaptador al protocolo `/call/<endpoint>` de
  Spaces Gradio (§6). Streaming de eventos SSE de Gradio → `Flow<LlmChunk>`.
  Tool-calling por prompt (mismo esquema delimitado que LOCAL).

### 3.3 AgentLoop (`backend/agent/AgentLoop.kt`)

```
send(userText):
  loop (máx 8 iteraciones):
    chunks = gateway.chatStream(history + toolsCatalog, tools)
    stream de Text a la UI en vivo
    si llegó ToolCall:
      pedir permiso (§7) -> ejecutar Tool en Dispatchers.IO
      history += assistant(toolCall) + tool(result)
      continuar bucle
    si terminó sin tool calls: entregar respuesta
```

- Cancelación ("Detener") corta el bucle completo y el stream.
- Tarjeta `ToolCallCard` en la UI: nombre + args + resultado colapsable
  (estado: en curso / OK / error).
- En modo LOCAL/Gradio, el catálogo se inyecta en el system prompt vía contrato
  de delimitadores (§3.2); la extracción es tolerante (si el JSON está incompleto
  por corte, se descarta el tool call y se entrega el texto parcial).

---

## 4. Sección B — Visualización de scripts y formatos (extensiones)

### 4.1 Renderer markdown propio (`frontend/Markdown.kt`)

Parser ligero Compose puro (sin dependencias multiplataforma pesadas, compatibles
con Compose 1.7.3): párrafos, listas, bold/italic/inline-code, tablas, encabezados,
**bloques de código con lenguaje** y (F5) math `$...$`/`$$...$$`.

### 4.2 Bloques de código (`frontend/CodeBlock.kt`)

- Header con el **lenguaje detectado del fence** (```kotlin, ```glsl, …) mapeado a
  su extensión real (`kotlin→.kt`, `python→.py`, `cpp→.cpp`, `glsl→.glsl`,
  `javascript→.js`, `html→.html`, `css→.css`, `json→.json`, `rust→.rs`, …).
- Syntax highlighting por tokenizador propio (keywords, strings, comentarios,
  números) con paleta que sigue al tema de la app. Sin emojis (regla de estilo).
- Botones: **Copiar**, **Guardar como…** (JFileChooser con nombre sugerido
  `nombre.{ext}`), y **Ver como** (código resaltado / texto plano).

### 4.3 Formatos del mensaje

- `/format <ext>` o petición libre ("mostralo como .py"): el modelo emite el
  contenido y el renderer respeta el lenguaje declarado en el fence.
- Exportar mensaje completo → `.md` o archivo suelto.

---

## 5. Sección C — Archivos: crear y descargar

### 5.1 Herramientas

- `create_file(name, content)` → escribe en `app-home/outputs/<chat-id>/`.
  Validación: sin `..`, sin rutas absolutas, extensión en lista permitida,
  tope de tamaño (5 MB por archivo).
- `download_url(url)` → descarga a `outputs/<chat-id>/` con streaming, lock por
  ruta (reutilizar patrón de `HuggingFaceDownloader`), límite de 50 MB, timeout,
  nombre deducido del `Content-Disposition`/URL. Devuelve tamaño y tipo.

### 5.2 UI de adjuntos

- `UiMessage` gana `attachments: List<UiAttachment(name, size, path, mime)>`.
- `AttachmentCard.kt`: tarjeta con ícono por tipo, nombre, tamaño, botones
  **Guardar** (→ JFileChooser) y **Abrir** (asociación del SO).

---

## 6. Sección D — Navegación web

### 6.1 Herramientas

- `web_search(query)` → `SearchProvider` con implementación
  `DuckDuckGoProvider` (API `https://duckduckgo.com/html/` o instant answer +
  fallback; sin claves). Devuelve hasta 8 resultados: título, URL, snippet.
- `web_fetch(url)` → GET con timeout 20 s, límite 2 MB, user-agent propio;
  si es HTML se convierte a texto (parser propio de tags, scripts y estilos
  removidos) y se recorta; devuelve título, URL final y texto.

**Estado en F4 (2026-10-20): implementado.** `backend/rag/agent/tools/WebTools.kt`
con el contrato `SearchProvider` y tres proveedores:

| Proveedor | Qué es | Notas |
|-----------|--------|-------|
| `DuckDuckGoProvider` | HTML de `html.duckduckgo.com` + API de instant answer | Motor principal, sin clave |
| `WikipediaProvider` | API de Wikipedia (`es` y `en`) | Respaldo, sin clave |
| `ChainSearchProvider` | Encadena los anteriores | El primero que responde gana |

DuckDuckGo no tiene API oficial sin clave y su HTML tiene un control
anti-bot: contesta con una página de verificación (HTTP 202) en lugar de
resultados. El proveedor lo resuelve sin hacer trampas:

1. **Prepara** el cliente: visita la portada una vez y conserva un cookie jar
   en memoria.
2. **Reintenta**: si llega la verificación, espera 800 ms y vuelve a pedir
   (hasta 3 intentos). La verificación es intermitente.
3. **Respalda**: si aun así no hay resultados, usa la API de instant answer.

Si tampoco eso sirve, la cadena pasa a Wikipedia: la herramienta **siempre**
devuelve algo y el modelo ve de qué motor salió cada resultado (`source`).
Los anuncios del buscador (`duckduckgo.com/y.js`) se descartan.

`web_fetch` usa un user-agent de navegador (muchos sitios rechazan los
clientes que no se parecen a uno), reconoce el charset de la respuesta, saca
script/estilo/svg, decodifica las entidades HTML (Latin-1, puntuación,
símbolos y griegas, más las numéricas) y **detecta binarios**: si la URL es un
PDF o una imagen responde "usá `download_url`" en vez de devolver basura.

### 6.2 Cadena de uso (caso de prueba del pedido original)

"Buscá la IA que genera el PDF, pasame el link de descarga" →
`web_search` → `web_fetch` (página del generador) → `download_url` (PDF) →
`AttachmentCard` con el PDF en el chat. Se documenta como escenario E2E de la F4.

---

## 7. Sección E — Permisos y comandos de chat

### 7.1 Categorías

`WEB` (web_search, web_fetch), `FILES` (create_file, download_url),
`MCP` (futuro). Cada `Tool` declara su categoría (`ToolCategory`); `NONE`
significa "sin efecto externo" (la hora, por ejemplo) y nunca pregunta nada.

### 7.2 Política por chat

- Estado por chat (persistido en `chats.json` junto al nodo): `grantedCats`.
- Default: **off** → cada ejecución muestra diálogo "El modelo quiere ejecutar
  `web_search('…')`. ¿Permitir? (Permitir siempre / Una vez / Cancelar)".
- Comandos en el redactor (patrón `/help` ya existente):
  - `/permitir-web` — concede WEB en el chat activo.
  - `/permitir-archivos` — concede FILES.
  - `/permisos` — muestra categorías concedidas y comando para revocar.
  - `/revocar-web`, `/revocar-archivos`.

**Estado en F3 (2026-10-20): implementado.** La puerta única es
`ToolPermissions.authorize(tool, argsJson, ctx)`, que devuelve
`ALLOW_ONCE` (ya concedido o sin efecto externo), `ALLOW_ALWAYS` o `DENY`.
La UI resuelve el diálogo con un `CompletableDeferred`: si la generación se
cancela con el diálogo abierto, el diálogo se cierra y la cancelación se
propaga.

Semántica de la denegación (decidida al implementar):

- La **primera** denegación no cuenta como error de herramienta: el modelo
  recibe el feedback ("el usuario no autorizó la herramienta, no la vuelvas a
  pedir") y sigue conversando.
- Si el modelo **insiste**, la segunda llamada se deniega automáticamente (sin
  volver a abrir el diálogo) y el bucle corta.

### 7.3 Límites globales

Máx. 8 iteraciones de tool loop; timeouts por herramienta; tamaño de outputs
(5 MB por archivo creado, 50 MB por descarga). La persistencia de la config
v4 (`configVersion: 4`) llega en F7.

---

## 8. Sección F — LaTeX

- **F5a — Math en chat**: `$...$` y `$$...$$` renderizados con **JLaTeXMath**
  (bitmap en memoria, offline). Streaming-friendly: se renderiza por fragmentos
  estables.
- **F5b — Documentos → PDF**: tool `latex_compile(document)` (categoría FILES):
  escribe el `.tex` en outputs, invoca `pdflatex`/`xelatex` detectado en PATH
  (TinyTeX/MiKTeX). Si no está instalado → resultado de error claro con guía
  de instalación (fail-fast, patrón del proyecto). El PDF sale como
  `AttachmentCard`.

### 8.1 F5a — matemática en el chat (JLaTeXMath)

- **`frontend/app/ui/MathRender.kt`** (nuevo): renderiza una fórmula TeX a un
  `BufferedImage` **en memoria** (sin escribir a disco, sin red). Se recolorea
  al `onSurface` del tema, así que anda en claro y en oscuro.
- **`frontend/app/ui/Markdown.kt`**: el parser de markdown reconoce la
  matemática como un nodo más (`MdNode.Math`) y la dibuja con
  `appendInlineContent` (inline) o como bloque (display).
- Reglas de parsing (a propósito, para no romper la prosa):
  - `$$…$$` = display, `$…$` = inline.
  - El `$` de cierre **no puede** ir seguido de un dígito: `$5 … $10` es texto,
    no matemática.
  - Un `$` sin cerrar se queda como texto plano (seguro con streaming).
  - El código en línea `` `…` `` nunca es matemática.
- **No** hace falta TeX instalado: JLaTeXMath es una biblioteca de Java pura.

### 8.2 F5b — `latex_compile`: LaTeX → PDF

- **`backend/rag/agent/tools/LatexTool.kt`** (nuevo): tool de la categoría
  **FILES** (pide permiso como `create_file`).
- El motor se busca en el **PATH** (sin lanzar procesos): `pdflatex` primero,
  `xelatex` después. El modelo puede pedir uno u otro con el argumento
  `engine`; si pide uno que no está, el error lo dice.
- **Fail-fast**: si no hay ningún motor, la herramienta devuelve un error con
  la guía de instalación (TeX Live / MiKTeX). Es preferible a un PDF que no
  sale.
- El `.tex` se escribe en `outputs/<chat-id>/`; si el modelo manda un
  fragmento sin `\documentclass`, se le envuelve en un preámbulo estándar
  (`article`, `inputenc`, `amsmath`, `geometry`).
- La compilación corre en un **directorio temporal**: los `.aux`, `.log` y
  demás no ensucian la carpeta del chat. Al final sólo quedan el `.tex` y el
  `.pdf`.
- Salida: `ToolResult.Ok` con dos `Artifact` (el PDF y el `.tex`), que la UI
  muestra como `AttachmentCard` (Abrir / Guardar).
- Tope: 1 MB de documento y 60 s de compilación.


---

## 9. Sección G — MCP (arquitectura preparada)

**Estado: IMPLEMENTADO (F7, 2026-10-20)** — contrato, adaptador, registro con
orígenes, configuración y pantalla. **El transporte (cliente MCP) NO está
implementado**: falta el SDK oficial de Kotlin.

### 9.1 El contrato (sin SDK)

`backend/rag/mcp/McpToolAdapter.kt` define el seam:

```kotlin
interface McpConnection {
    val serverId: String
    val displayName: String
    suspend fun listTools(): List<McpToolDescriptor>
    suspend fun callTool(name: String, args: JsonObject): McpToolResult
    suspend fun close()
}
```

Tipos que lo acompañan: `McpToolDescriptor` (nombre, descripción, JSON Schema
de los argumentos), `McpToolResult` (`Ok` con contenido / `Error`) y
`McpContent` (`Text` o `Binary` en base64).

### 9.2 La tool remota es una tool

`McpToolAdapter` implementa `Tool`:

- **Nombre calificado**: `mcp__<servidor>__<herramienta>`. Dos servidores
  pueden exponer tools homónimas sin pisarse, y el modelo (y el usuario) ven
  de dónde sale cada llamada.
- **Categoría MCP**: pide permiso como cualquier tool con efecto externo
  (`/permitir-mcp`, apagado por defecto y por chat).
- **Origen MCP**: `ToolOrigin.MCP`, para poder listarlas por separado.
- **Binarios como adjuntos**: el contenido binario se guarda en la carpeta del
  chat y aparece como adjunto, igual que un PDF de `latex_compile`.

El `AgentLoop`, la puerta de permisos, el catálogo y la UI de adjuntos **no
distinguen el origen**: una tool MCP es una tool.

### 9.3 El manager

`backend/rag/mcp/McpManager.kt` pide conexiones al `McpConnector` (el seam del
transporte), arma los adaptadores y expone el estado de cada servidor
(conectado / no conectado / por qué). Un servidor que falla no impide que los
demás funcionen.

`PendingMcpConnector` es el conector por defecto: **todavía no hay
transporte**, así que responde con un error claro ("falta el cliente
oficial"). El estado de cada servidor lo muestra `/mcp` y la pantalla
**Ajustes → Herramientas**: el usuario nunca ve un servidor "conectado" que no
responde.

### 9.4 Qué falta para hablar MCP de verdad

1. Agregar el SDK oficial (`io.modelcontextprotocol:kotlin-sdk`).
2. Implementar `McpConnector` sobre stdio (proceso local) y/o HTTP.
3. Cambiar el conector en un solo lugar (el `ChatViewModel`): el resto de la
   app no se toca.

Los servidores se **declaran** en la sección `mcp` de la config (id, nombre,
transporte, comando/args/env o URL, habilitado). Esa configuración es la que
va a usar el cliente cuando llegue.

---

## 10. Sección H — HF Spaces Gradio (gateway remoto) — PENDIENTE, NO DECIDIDO

**Estado: INCOMPLETO (2026-10-19).** El usuario eligió originalmente "HF Spaces
(Gradio)" pero al ver la explicación del riesgo pidió dejarlo incompleto. La F6
**queda congelada**: no se implementa nada hasta que se decida entre
HF Inference Providers (OpenAI-compatible, recomendado), otro proveedor o
descartar HF por completo.

Notas por si se retoma:

- `llm-api/GradioSpaceGateway.kt` implementaría `LlmGateway` sobre el protocolo
  de Spaces:
  - `GET https://<user>-<space>.hf.space/config` → parámetros del Space (para
    validar que expone un endpoint de chat).
  - `POST /call/<fn>` con los mensajes serializados al formato del Space;
    streaming vía eventos SSE `/call/<fn>/queue` (dataclasses de Gradio).
- La serialización de mensajes depende de la firma de cada Space → se parametriza
  con una plantilla en la config del preset de API (`gradioTemplate`).
- Tool-calling por prompt (mismo delimitador que LOCAL).
- **Riesgo aceptado**: protocolo no estable; el dueño del Space puede cambiar la
  firma. Mitigación: el adaptador valida al conectar y da error claro.
- Nota: cada Space tiene su propia forma de "modelo"; `modelName` en el preset se
  usa como identificador libre del Space elegido.

---

## 11. Arquitectura objetivo (archivos nuevos/modificados)

Rutas actualizadas a la estructura modular (`docs/ARCHITECTURE.md`); lo que ya existe
(F1, F2) va marcado con ✅.

```
backend/
  rag/agent/Tool.kt                 ✅ ToolSpec, Tool, ToolResult, ToolContext,
                                   ✅ ToolCategory, ToolOrigin, PermissionRequest/Decision, Artifact
  rag/agent/ToolRegistry.kt         ✅ registro con origen LOCAL|MCP (F7)
  rag/agent/AgentLoop.kt            ✅ bucle de tool calling (+ timeout por tool, F7)
  rag/agent/ToolPermissions.kt      ✅ puerta única de permisos
  rag/agent/ToolCallSanitizer.kt    ✅ reparación del JSON que devuelve el modelo
  rag/agent/PromptToolParser.kt     ✅ parser del bloque ```tool_call (modo LOCAL)
  rag/agent/tools/GetTimeTool.kt    ✅ herramienta de prueba
  rag/agent/tools/WebTools.kt       ✅ web_search, web_fetch (+ SearchProvider)
  rag/agent/tools/FileTools.kt      ✅ create_file, download_url
  rag/agent/tools/LatexTool.kt      ✅ latex_compile (F5b)
  rag/mcp/McpToolAdapter.kt         ✅ contrato McpConnection + adaptador (F7)
  rag/mcp/McpManager.kt             ✅ manager de servidores + estado (F7)
  rag/mcp/McpConfig.kt              ✅ sección `mcp` de la config (F7)
  rag/config/ToolsConfig.kt         ✅ secciones tools/outputs/latex (F7)
llm-api/
  llm/api/LlmGateway.kt             ✅ + LlmChunk tipado y tools en chatStream
  llm/api/Tools.kt                  ✅ ToolSpec (catálogo para el request)
  llm/api/GradioSpaceGateway.kt     # HF Spaces (§10)
frontend/
  app/ui/Markdown.kt                ✅ renderer
  app/ui/MathRender.kt              ✅ F5a: JLaTeXMath -> imagen en memoria
  app/ui/CodeBlock.kt               ✅ highlight + copiar + guardar/exportar
  app/chat/ChatTools.kt             ✅ registro de tools + ajustes (F7)
  app/chat/ToolCallCard.kt          ✅ tarjeta de herramienta en uso
  app/chat/ChatViewModel.kt         ✅ AgentLoop, permisos por chat, /comandos
  app/chat/ChatCommands.kt          ✅ comandos de barra (extensión del ViewModel)
  app/chat/ChatPermissions.kt       ✅ estado de permisos + handshake con la UI
  app/chat/PermissionDialog.kt      ✅ diálogo "¿Permitir?"
  app/chat/AttachmentCard.kt        ✅ adjuntos descargables
  app/screens/ToolsSettings.kt      ✅ Ajustes → Herramientas (F7)
```

Config **v4** (migración automática desde v3): secciones `tools`, `outputs`,
`latex` y `mcp`. Los valores por defecto son los mismos que usaban las
constantes del código, así que una instalación migrada se comporta igual.

---

## 12. Fases de implementación

| Fase | Contenido | Dependencias | Estado |
|------|-----------|--------------|--------|
| **F1** | LlmChunk + tools en gateway OpenAI + AgentLoop + ToolRegistry + ToolCallCard + tool de prueba (`get_time`) | — | ✅ 2026-10-20 |
| **F2** | Renderer markdown + CodeBlock con highlight + extensiones + Guardar como… | — | ✅ 2026-10-20 |
| **F3** | `create_file` + `download_url` + AttachmentCard + permisos FILES + `/permitir-archivos` | F1 | ✅ 2026-10-20 |
| **F4** | `web_search` (DuckDuckGo + respaldo Wikipedia) + `web_fetch` + permisos WEB + `/permitir-web` + escenario E2E del PDF | F1, F3 | ✅ 2026-10-20 |
| **F5** | Math con JLaTeXMath + `latex_compile` → PDF (autodetección TinyTeX/MiKTeX) | F2, F3 | ✅ 2026-10-20 |
| **F6** | `GradioSpaceGateway` para HF Spaces | **CONGELADA** — decisión pendiente (ver §10) | — |
| **F7** | Contrato MCP + registro con orígenes + config v4 + pantalla de permisos + documentación | F1–F4 | ✅ 2026-10-20 |
| **F8** | Cliente MCP real (SDK oficial) sobre stdio/HTTP | F7 | Pendiente |
| **T** | Infraestructura de tests (JUnit 5) + 157 tests deterministas | — | ✅ 2026-10-20 |

F2 es independiente de F1 y puede hacerse en paralelo.

---

## 13. Riesgos

| Riesgo | Impacto | Mitigación |
|--------|---------|------------|
| Phi-4-mini genera JSON de tool call inválido o alucina tools | Falla del loop | Delimitador estricto + validación JSON + degradar a respuesta normal con nota |
| Space Gradio cambia de firma | Se rompe el gateway | Validación al conectar + error claro + plantilla parametrizable |
| `pdflatex` ausente | LaTeX-docs no funciona | **Mitigado (F5b)**: la tool falla rápido con guía de instalación; la math del chat (JLaTeXMath) no necesita TeX |
| Bloques de código durante streaming | Render parcial raro | Render por fragmentos estables; el fence abierto se muestra como texto hasta cerrar |
| Contexto local (8k) con tools + contexto RAG | Respuestas degradadas | Catálogo de tools compacto; en modo Docs priorizar contexto RAG |
| Buscador sin clave con control anti-bot | `web_search` se queda sin resultados | Preparar el cliente + reintentos + respaldo automático (Wikipedia); la herramienta siempre responde |

---

## 14. Reglas de estilo (heredadas de `.agentes/`)

- Sin emojis en código, strings, comentarios ni UI (iconos Material o símbolos
  Unicode simples si hace falta).
- I/O bloqueante en `Dispatchers.IO`; UI nunca se bloquea.
- Errores tipados (`LlmException` + códigos), fail-fast en configuración.
- Documentación al día: `ARCHITECTURE.md`, `TAREAS.md`, `CHANGES.md`.
