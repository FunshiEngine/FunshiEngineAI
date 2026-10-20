# FunshiEngineAI

Asistente con IA para el motor gráfico **FunshiEngine**: construye código básico y sugiere
documentación según las necesidades del usuario y su base de conocimientos, **sin inventar
información** — si no hay una referencia que lo respalde, lo dice.

## Qué estamos montando

- **`frontend/`** — Chat de escritorio (Jetpack Compose Desktop). Render propio de markdown y
  bloques de código, tarjetas de herramientas y árbol de chats con carpetas.
- **`backend/`** — RAG (Retrieval-Augmented Generation): indexa fuentes, trocea documentos en
  chunks, recupera los más relevantes (BM25) y arma el prompt con el system prompt editable.
  Además, el **bucle de herramientas** (`rag.agent`): el modelo puede pedir acciones a la app.
- **`llm-api/`** — Fachada de LLM: descarga el modelo GGUF, levanta `llama-server` (llama.cpp)
  en local y expone un `LlmGateway` con streaming SSE compatible con OpenAI. También puede
  apuntar a cualquier API compatible (Ollama, LM Studio, vLLM, OpenAI…).
- **`estructuras/`** — Estructuras de datos reutilizables (árboles, colas, pilas).

Cada módulo está dividido en paquetes por área (`llm.api`, `rag.agent`, `app.chat`…) — el mapa
completo está en [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

### Fuentes de conocimiento (RAG)

| Fuente | Estado | Descripción |
|--------|--------|-------------|
| Repos de GitHub | ✅ | `githubSources` en la config descarga el zipball y indexa `.md`, `.txt`, `.rst`, `.adoc`… |
| System prompt | ✅ | `config/system_prompt.md` — editable sin reiniciar, define el rol y las reglas. |
| Docs internas | ✅ | `internal-docs/` — carpeta local de documentos. |
| Docs por motor | ✅ | `api-docs/<id>/` — documentación propia de cada API (y del modelo local). |
| Manual del FunshiEngine | 📋 futuro | Un `.md` de documentación del motor, pensado como **manual de uso para personas que
hacen juegos** (no para quienes tocan el código del motor): mezcla de guía, recetas y
referencia de API. |

### Capacidades del asistente

- Construir **código básico** (escenas, entidades, shaders sencillos, scripts) usando como
  referencia la documentación indexada.
- **Sugerir documentación** según la necesidad del usuario (qué falta, qué conviene escribir).
- Responder preguntas citando las fuentes con números (`[1]`, `[2]`).
- **Renderizar** la respuesta como markdown: títulos, listas, tablas, citas y **bloques de
  código con resaltado**, copiar y "guardar como…" con la extensión real (`.py`, `.kt`,
  `.cpp`, `.glsl`…).
- **Tool calling**: el modelo puede pedir herramientas a la app (hoy `get_time`,
  `create_file`, `download_url`, `web_search`, `web_fetch` y `latex_compile`); funciona en modo nativo contra APIs
  OpenAI-compatible y en modo protocolo-por-prompt contra el modelo local.
- **Buscar en la web** sin clave de API: `web_search` (DuckDuckGo, con respaldo automático en
  Wikipedia) y `web_fetch` (trae la página y la devuelve como texto). Si la URL es un PDF o una
  imagen, la herramienta avisa que use `download_url` para dejarla como adjunto.
- **Matemática y LaTeX**: las fórmulas `$…$` y `$$…$$` se **dibujan** en el chat (JLaTeXMath,
  offline, sin TeX instalado), y la tool `latex_compile` convierte un documento LaTeX en **PDF**
  (adjunto listo para abrir o guardar). Si no hay motor de TeX en el sistema, la herramienta
  avisa con una guía de instalación en vez de fallar en silencio.
- **Permisos por categoría**: están **apagados por defecto** y se conceden **por chat**
  (`/permitir-archivos`, `/permitir-web`, `/permisos`, `/revocar-*`). Sin permiso concedido, la
  herramienta pide confirmación con un diálogo ("Permitir siempre / Una vez / Cancelar") y los
  archivos que produce llegan al chat como **adjuntos** que se pueden abrir o guardar.

## Regla central: no inventar sin referencia

El system prompt obliga a responder solo con el bloque `CONTEXTO` recuperado por el RAG y a
decir "No encuentro eso en la documentación" cuando falta. Para hacer esto **determinista**
en vez de depender de la buena voluntad del modelo, la idea es un verificador post-generación:

1. La respuesta declara sus fuentes (citas `[n]` → chunk indexado, ya existe el mapeo).
2. Un script re-abre cada fuente citada y comprueba que la cita (o su fragmento) existe
   **literalmente** en el chunk/documento original.
3. Si toda cita se verifica → respuesta válida. Si alguna no existe → se marca
   "sin respaldo" y se reintenta o se responde "no lo encuentro en la documentación".

Esto convierte "no inventes" de una instrucción blanda en una comprobación programática.
Estado: diseño (ver `.agentes/` — carpeta local, no versionada).

## Configuración

Todo se configura en `app-home/config/app-config.json` (se crea solo en el primer arranque):

```jsonc
{
  "llm": {
    "configVersion": 3,
    "mode": "LOCAL",                  // LOCAL (llama-server) | REMOTE (API compatible OpenAI)
    "remoteBaseUrl": "http://localhost:11434/v1",
    "apiKey": "",
    "modelName": "phi-4-mini",
    "hfRepo": "unsloth/Phi-4-mini-instruct-GGUF",
    "hfFile": "Phi-4-mini-instruct-Q4_K_M.gguf",
    "contextSize": 8192,
    "gpuLayers": 0,                   // >0 si tienes GPU con VRAM suficiente
    "temperature": 0.2,
    "maxTokens": 1024,
    "chatTimeoutMs": 0,               // 0 = sin límite de tiempo por respuesta
    "loadTimeoutMs": 180000,          // espera máxima a llama-server
    "llamaServerPath": "",            // vacío = autodetecta (clon compilado → PATH)
    "llamaRepoUrl": "https://github.com/ggml-org/llama.cpp.git",
    "llamaRepoVersion": "",           // rama/tag (vacío = HEAD)
    "llamaRepoDir": "",               // vacío = <app-home>/llama.cpp
    "gitBin": "git",
    "buildFromSource": true,          // true = clona y compila llama-server con cmake
    "localPort": 8081,                // si está ocupado, prueba los 19 siguientes
    "modelsDir": "models",
    "hfToken": "",                    // solo si el repo de HF lo exige
    "modelCatalog": [ … ],            // catálogo de modelos (pantalla Modelos)
    "apiPresets": [ … ]               // colección de APIs (pantalla Motor)
  },
  "rag": {
    "githubSources": [ { "owner": "…", "repo": "…", "ref": "", "paths": ["docs/"] } ],
    "internalDocsDirs": ["internal-docs"],
    "systemPromptFile": "config/system_prompt.md",
    "chunkSize": 1200,
    "topK": 5
  }
}
```

Los chats (carpetas, mensajes, tarjetas de herramientas y adjuntos) se guardan aparte, en
`app-home/config/chats.json`. Los archivos que crea o descarga el modelo van a
`app-home/outputs/<chat-id>/`.

**Modelo local por defecto**: `Phi-4-mini-instruct-Q4_K_M.gguf`
(`https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf`,
~2.4 GB) — rápido y bueno en español; se descarga solo al primer arranque, con reanudación si se corta.

**Requisitos**: JDK 21, Gradle, y `llama-server` de llama.cpp en el PATH (o indicar la ruta en
`llm.llamaServerPath`). Opcional: **TeX Live o MiKTeX** en el PATH, sólo para la tool
`latex_compile` (la matemática del chat funciona sin él).

## Pantallas

La barra lateral (colapsable) tiene cuatro secciones:

- **Chat**: conversaciones con carpetas anidables, drag & drop, click derecho; burbujas con
  copiar/reiniciar, redactor con cola de mensajes mientras la IA responde, tarjetas de
  herramientas, adjuntos descargables y el estado de permisos del chat.
- **Modelos**: catálogo con Descargar (barra + Cancelar), Usar (reinicia el motor con ese
  modelo), agregar repo/archivo de HF (con "Listar .gguf").
- **Motor**: segmentado LOCAL/REMOTE, colección de APIs compatibles con OpenAI
  (Ollama/LM Studio de ejemplo) con "Probar conexión", documentación propia por API y
  "Volver a modelo local".
- **Config**: tema claro/oscuro y color de acento.

## Ejecutar

```bash
./gradlew :frontend:run
```

En desarrollo todo (config, modelos, docs internas) vive en `<raíz>/app-home`.

## Estado / roadmap

- [x] Chat local con streaming + RAG (BM25) sobre GitHub y docs internas.
- [x] Descarga del modelo con reanudación y arranque de `llama-server`.
- [x] Validación estricta de config, errores tipados y robustez del servidor local.
- [x] **F1 — tool calling**: bucle modelo→herramienta→modelo, nativo en REMOTE y por prompt
      en LOCAL, con reparación del JSON que devuelve el modelo.
- [x] **F2 — markdown y código**: renderizador propio, bloques de código con resaltado,
      copiar y "guardar como…" con extensiones reales.
- [x] **F3 — archivos y permisos**: `create_file` y `download_url` (adjuntos que se abren o se
      guardan desde el chat), permisos **por categoría, apagados por defecto y por chat**
      (`/permitir-archivos`, `/permisos`, `/revocar-*`).
- [x] **F4 — web**: `web_search` (DuckDuckGo sin clave, con respaldo en Wikipedia) y `web_fetch`
      (página → texto legible), permisos `WEB` por chat (`/permitir-web`).
- [x] **F5 — LaTeX**: las fórmulas `$…$` y `$$…$$` se dibujan en el chat (JLaTeXMath, offline) y
      la tool `latex_compile` convierte un documento LaTeX en PDF (adjunto), con fail-fast y guía
      de instalación si no hay motor de TeX.
- [x] **Arquitectura modular**: un paquete por área en los tres módulos.
- [ ] **F6–F7** (en curso, plan en `docs/PLAN-INTEGRACION.md`): HF Spaces (Gradio, **congelada**) y
      MCP preparado (contrato de conexión + registro con origen).
- [ ] Manual de uso del FunshiEngine como fuente RAG.
- [ ] Verificador determinista de citas (script post-generación).
- [ ] Tests unitarios del `ChatViewModel` (cola, arranque, cancelación) y de integración de
      `KnowledgeBase.sync()` + `RagEngine.ask()`.
