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
    /** Ejecuta la herramienta y devuelve un resultado apto para devolver al modelo. */
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

sealed class ToolResult {
    data class Ok(val text: String) : ToolResult()          // el texto va al modelo
    data class Error(val message: String) : ToolResult()    // error legible
}
```

`ToolContext` lleva: chat activo, carpeta `outputs/<chat-id>`, política de permisos,
dispatcher IO y el `UiProgress` para reportar estado.

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

### 6.2 Cadena de uso (caso de prueba del pedido original)

"Buscá la IA que genera el PDF, pasame el link de descarga" →
`web_search` → `web_fetch` (página del generador) → `download_url` (PDF) →
`AttachmentCard` con el PDF en el chat. Se documenta como escenario E2E de la F4.

---

## 7. Sección E — Permisos y comandos de chat

### 7.1 Categorías

`WEB` (web_search, web_fetch), `FILES` (create_file, download_url),
`MCP` (futuro). Cada `Tool` declara su categoría.

### 7.2 Política por chat

- Estado por chat (persistido en `chats.json` junto al nodo): `grantedCats`.
- Default: **off** → cada ejecución muestra diálogo "El modelo quiere ejecutar
  `web_search('…')`. ¿Permitir? (Permitir siempre / Una vez / Cancelar)".
- Comandos en el redactor (patrón `/help` ya existente):
  - `/permitir-web` — concede WEB en el chat activo.
  - `/permitir-archivos` — concede FILES.
  - `/permisos` — muestra categorías concedidas y comando para revocar.
  - `/revocar-web`, `/revocar-archivos`.

### 7.3 Límites globales

Máx. 8 iteraciones de tool loop; timeouts por herramienta; tamaño de outputs.
Persistencia en config v4 (`configVersion: 4`).

---

## 8. Sección F — LaTeX

- **F5a — Math en chat**: `$...$` y `$$...$$` renderizados con **JLaTeXMath**
  (bitmap en memoria, offline). Streaming-friendly: se renderiza por fragmentos
  estables.
- **F5b — Documentos → PDF**: comando `/latex` o tool `latex_compile(document)`
  (categoría FILES): escribe `doc.tex` en outputs, invoca `pdflatex`/`xelatex`
  detectado en PATH (TinyTeX/MiKTeX). Si no está instalado → resultado de error
  claro con guía de instalación (fail-fast, patrón del proyecto). El PDF sale
  como `AttachmentCard`.

---

## 9. Sección G — MCP (arquitectura preparada)

- `backend/mcp/McpToolAdapter.kt`: define el contrato de conexión sin depender
  aún del SDK:

```kotlin
interface McpConnection {
    suspend fun listTools(): List<ToolSpec>
    suspend fun callTool(name: String, args: JsonObject): ToolResult
    suspend fun close()
}
```

- `ToolRegistry` acepta tools de origen `LOCAL | MCP` indistintamente: cuando en
  la F6 se agregue el SDK oficial Kotlin (`io.modelcontextprotocol:kotlin-sdk`),
  `McpServerConnection` implementa `McpConnection` sobre stdio/HTTP y cada tool
  remota se adapta a un `Tool` del registro con categoría `MCP`.
- Config: `mcp.servers[]` (comando/args/URL, habilitado) ya contemplada en v4.
- **No se implementa el cliente en esta ronda** (el usuario no tiene servidores
  aún); sí el contrato, el registro con orígenes y los lugares de extensión.

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

```
backend/
  agent/Tool.kt              # ToolSpec, Tool, ToolResult, ToolContext
  agent/ToolRegistry.kt      # registro con origen LOCAL|MCP
  agent/AgentLoop.kt         # bucle de tool calling
  agent/tools/WebTools.kt    # web_search, web_fetch (+ SearchProvider)
  agent/tools/FileTools.kt   # create_file, download_url
  agent/tools/LatexTool.kt   # latex_compile (F5b)
  mcp/McpToolAdapter.kt      # contrato McpConnection (preparación)
llm-api/
  LlmGateway.kt              # + LlmChunk tipado y tools en chatStream
  GradioSpaceGateway.kt      # HF Spaces (§10)
frontend/
  Markdown.kt                # renderer
  CodeBlock.kt               # highlight + guardar/exportar
  AttachmentCard.kt          # adjuntos descargables
  ToolCallCard.kt            # tarjeta de herramienta en uso
  ChatViewModel.kt           # AgentLoop, permisos por chat, /comandos
```

Config v3 → v4: secciones `tools`, `outputs`, `latex`, `mcp` con migración
automática (patrón `configVersion` existente).

---

## 12. Fases de implementación

| Fase | Contenido | Dependencias |
|------|-----------|--------------|
| **F1** | LlmChunk + tools en gateway OpenAI + AgentLoop + ToolRegistry + ToolCallCard + tool de prueba (`get_time`) | — |
| **F2** | Renderer markdown + CodeBlock con highlight + extensiones + Guardar como… | — |
| **F3** | `create_file` + `download_url` + AttachmentCard + permisos FILES + `/permitir-archivos` | F1 |
| **F4** | `web_search` (DuckDuckGo) + `web_fetch` + permisos WEB + `/permitir-web` + escenario E2E del PDF | F1, F3 |
| **F5** | Math con JLaTeXMath + `latex_compile` → PDF (autodetección TinyTeX/MiKTeX) | F2, F3 |
| **F6** | `GradioSpaceGateway` para HF Spaces | **CONGELADA** — decisión pendiente (ver §10) |
| **F7** | Contrato MCP + registro con orígenes + config v4 + pantalla de permisos + documentación | F1–F4 |

F2 es independiente de F1 y puede hacerse en paralelo.

---

## 13. Riesgos

| Riesgo | Impacto | Mitigación |
|--------|---------|------------|
| Phi-4-mini genera JSON de tool call inválido o alucina tools | Falla del loop | Delimitador estricto + validación JSON + degradar a respuesta normal con nota |
| Space Gradio cambia de firma | Se rompe el gateway | Validación al conectar + error claro + plantilla parametrizable |
| `pdflatex` ausente | LaTeX-docs no funciona | Fail-fast con guía de instalación (math igual funciona) |
| Bloques de código durante streaming | Render parcial raro | Render por fragmentos estables; el fence abierto se muestra como texto hasta cerrar |
| Contexto local (8k) con tools + contexto RAG | Respuestas degradadas | Catálogo de tools compacto; en modo Docs priorizar contexto RAG |

---

## 14. Reglas de estilo (heredadas de `.agentes/`)

- Sin emojis en código, strings, comentarios ni UI (iconos Material o símbolos
  Unicode simples si hace falta).
- I/O bloqueante en `Dispatchers.IO`; UI nunca se bloquea.
- Errores tipados (`LlmException` + códigos), fail-fast en configuración.
- Documentación al día: `ARCHITECTURE.md`, `TAREAS.md`, `CHANGES.md`.
