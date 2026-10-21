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
- **`voice-api/`** — Fachada de voz: captura el micrófono, detecta el fin de la frase y
  transcribe (dictado) o sintetiza la respuesta (locución). Motores remotos compatibles con
  OpenAI o **locales** (whisper.cpp, motor de voz del sistema). Sin SDK de voz y sin ktor.
- **`estructuras/`** — Estructuras de datos reutilizables (árboles, colas, pilas).

Cada módulo está dividido en paquetes por área (`llm.api`, `rag.agent`, `app.chat`…) — el mapa
completo está en [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

### Documentación

| Documento | Para qué |
|-----------|----------|
| [`docs/MANUAL-RAG.md`](docs/MANUAL-RAG.md) | **Manual de uso**: cómo cargar la documentación, cómo se recupera, cómo se citan las fuentes y cómo leer el distintivo de citas. |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Cómo está hecho por dentro (paquetes, RAG, tool calling, render, concurrencia). |
| [`docs/PLAN-INTEGRACION.md`](docs/PLAN-INTEGRACION.md) | Estado de cada fase, criterios de aceptación y decisiones pendientes. |
| [`.agentes/plan-voz.md`](.agentes/plan-voz.md) | El plan de la voz: análisis, decisiones de diseño, arquitectura y tareas. |
| [`docs/TESTING.md`](docs/TESTING.md) | Los tests, cómo correrlos y qué reglas siguen. |
| [`docs/CHANGES.md`](docs/CHANGES.md) | Historial de cambios por versión. |

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
  (`/permitir-archivos`, `/permitir-web`, `/permitir-mcp`, `/permisos`, `/revocar-*`). Sin permiso
  concedido, la herramienta pide confirmación con un diálogo ("Permitir siempre / Una vez /
  Cancelar") y los archivos que produce llegan al chat como **adjuntos** que se pueden abrir o
  guardar.
- **MCP de verdad por stdio**: el servidor se lanza como proceso hijo y se le habla JSON-RPC 2.0
  (`initialize` → `tools/list` → `tools/call`); sus herramientas aparecen en el chat como
  `mcp__<servidor>__<herramienta>` y piden permiso como las demás (`/permitir-mcp`). Los servidores
  se declaran en **Ajustes → Herramientas** y `/mcp` muestra el estado con el motivo de cada
  fallo. El cliente es propio (el SDK oficial exige subir el toolchain del proyecto a Kotlin 2.4);
  el transporte **http** todavía no está implementado y la app lo dice.
- **Voz (dictado y chat por voz)**: el micrófono del redactor **dicta** (el texto transcrito
  queda en el redactor para que lo revises: no se manda solo) y el botón de voz mantiene un
  bucle *escuchar → responder → hablar*. Lo importante: **el texto transcrito entra por el
  mismo `send()` que el teclado**, así que el chat por voz usa el RAG, las herramientas, los
  permisos, las citas y la persistencia exactamente igual. Motores de dictado y locución:
  API compatible con OpenAI o **locales** (whisper.cpp y el motor de voz del sistema), así que
  el audio puede no salir nunca de tu máquina. Se configuran en **Ajustes → Voz** y con
  `/voz`, `/dictar` y `/voz-off`.

## Regla central: no inventar sin referencia

El system prompt obliga a responder solo con el bloque `CONTEXTO` recuperado por el RAG y a
decir "No encuentro eso en la documentación" cuando falta. Eso es una instrucción blanda: un
modelo chico puede parafrasear, exagerar o inventar. Por eso hay un **verificador
post-generación** que lo convierte en una comprobación programática:

1. La respuesta declara sus fuentes (citas `[n]` → chunk indexado, ya existe el mapeo).
2. Tras cada respuesta en modo Docs, la app **reabre cada fuente citada** y comprueba que el
   fragmento citado esté respaldado por ella (`rag/engine/CitationVerifier.kt`):
   - literal (el fragmento aparece en la fuente), o
   - sin detalles inventados (los nombres propios y los números que dice tienen que estar en la
     fuente), o
   - paráfrasis (suficientes palabras en común, admitiendo plurales y variaciones de verbo).
3. Cada respuesta muestra un distintivo: **"Citas verificadas (n)"** si todo está respaldado, o
   cuántas citas quedan **sin respaldo** (o apuntan a una fuente inexistente).

Es determinista: no llama al modelo ni a la red, así que se puede testear al 100% y nunca
falla por culpa de la red.

El manual completo de uso (cómo cargar la documentación, cómo se recupera, cómo se citan
las fuentes) está en [`docs/MANUAL-RAG.md`](docs/MANUAL-RAG.md).

## Configuración

Todo se configura en `app-home/config/app-config.json` (se crea solo en el primer arranque):

```jsonc
{
  "llm": {
    "configVersion": 5,
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
  },
  "tools": {
    "enabled": true,                 // interruptor general de herramientas
    "timeoutMs": 60000               // timeout por herramienta (0 = sin límite)
  },
  "outputs": {
    "dir": "outputs",                // carpeta de archivos (dentro de app-home)
    "maxFileMb": 5,                  // tope de create_file
    "maxDownloadMb": 50              // tope de download_url
  },
  "latex": {
    "engine": "",                    // vacío = autodetectar (pdflatex, luego xelatex)
    "timeoutMs": 60000,              // timeout de compilación
    "maxDocumentKb": 1024            // tope del documento
  },
  "mcp": {
    "timeoutMs": 30000,               // timeout de las peticiones MCP
    "servers": [                     // servidores MCP declarados (transporte stdio)
      { "id": "docs", "name": "Documentación", "transport": "stdio",
        "command": "mi-servidor-mcp", "args": [], "env": {}, "enabled": true }
    ]
  },
  "voice": {
    "enabled": false,                // apagada por defecto: la migración no cambia nada
    "stt": {
      "provider": "NONE",            // NONE | OPENAI | WHISPER_CLI
      "baseUrl": "",                 // vacío = hereda la del motor de inferencia activo
      "apiKey": "",                  // vacío = hereda la del motor de inferencia activo
      "model": "whisper-1",
      "language": "es",              // vacío = que lo detecte el motor
      "whisperCliPath": "",          // vacío = autodetectar en el PATH (con WHISPER_CLI)
      "whisperModelPath": "",        // modelo GGML de whisper (con WHISPER_CLI)
      "timeoutMs": 60000
    },
    "tts": {
      "provider": "NONE",            // NONE | OPENAI | SYSTEM
      "baseUrl": "",                 // vacío = hereda la del motor de inferencia activo
      "apiKey": "",
      "model": "tts-1",
      "voice": "alloy",
      "speed": 1.0,
      "timeoutMs": 60000
    },
    "capture": {
      "sampleRate": 16000,           // 16 kHz es lo que pide whisper
      "silenceThreshold": 0.02,      // umbral de silencio (RMS 0..1)
      "silenceTimeoutMs": 1500,      // silencio seguido que corta la frase
      "minRecordingMs": 400,         // por debajo, la captura se descarta como ruido
      "maxRecordingMs": 30000        // tope de la grabación
    },
    "speakAnswers": true,            // leer en voz alta la respuesta del chat por voz
    "maxSpeakChars": 1200            // tope de caracteres hablados
  }
}
```

Las secciones `tools`, `outputs`, `latex` y `mcp` se editan también desde **Ajustes →
Herramientas** (la app las guarda al instante). La sección `voice` se edita desde **Ajustes →
Voz**. Una config v3 se migra sola a la v5 y queda exactamente igual de comportamiento.

Los chats (carpetas, mensajes, tarjetas de herramientas y adjuntos) se guardan aparte, en
`app-home/config/chats.json`. Los archivos que crea o descarga el modelo van a
`app-home/outputs/<chat-id>/`.

**Modelo local por defecto**: `Phi-4-mini-instruct-Q4_K_M.gguf`
(`https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf`,
~2.4 GB) — rápido y bueno en español; se descarga solo al primer arranque, con reanudación si se corta.

**Requisitos**: JDK 21, Gradle, y `llama-server` de llama.cpp en el PATH (o indicar la ruta en
`llm.llamaServerPath`). Opcional: **TeX Live o MiKTeX** en el PATH, sólo para la tool
`latex_compile` (la matemática del chat funciona sin él). Opcional para la voz: **whisper.cpp**
(`whisper-cli` + su modelo GGML) para el dictado local, y un motor de voz del sistema
(`powershell`/`say`/`espeak-ng`) para la locución local; si falta alguno, la app lo dice con
una guía de instalación.

## Pantallas

La barra lateral (colapsable) tiene cuatro secciones:

- **Chat**: conversaciones con carpetas anidables, drag & drop, click derecho; burbujas con
  copiar/reiniciar, redactor con cola de mensajes mientras la IA responde, tarjetas de
  herramientas, adjuntos descargables, el estado de permisos del chat y la **barra de voz**
  (dictado y chat por voz).
- **Modelos**: catálogo con Descargar (barra + Cancelar), Usar (reinicia el motor con ese
  modelo), agregar repo/archivo de HF (con "Listar .gguf").
- **Motor**: segmentado LOCAL/REMOTE, colección de APIs compatibles con OpenAI
  (Ollama/LM Studio de ejemplo) con "Probar conexión", documentación propia por API y
  "Volver a modelo local".
- **Config**: tema claro/oscuro, color de acento y la sección **Voz** (motores de dictado y
  locución, formato de captura y topes).

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
- [x] **F7 — MCP preparado**: contrato (`McpConnection`), adaptador, registro con origen
      `LOCAL|MCP`, configuración v4 (`tools`, `outputs`, `latex`, `mcp`) y pantalla **Ajustes →
      Herramientas**.
- [x] **F8 — cliente MCP real (stdio)**: JSON-RPC 2.0 propio detrás del seam `McpConnector`
      (sin SDK oficial: pediría Kotlin 2.4 + ktor 3.5 para todo el proyecto). El transporte http
      queda pendiente.
- [x] **V1 — voz**: dictado y chat por voz sobre el **mismo** `send()` que el teclado, con
      motores de STT/TTS remotos o locales (whisper.cpp), sección de config `voice` (v5,
      apagada por defecto) y Ajustes → Voz.
- [x] **Arquitectura modular**: un paquete por área en los tres módulos.
- [x] **Tests**: 376 tests deterministas con JUnit 5 (`./gradlew test`), reglas en
      `docs/TESTING.md`.
- [x] **Verificador determinista de citas**: cada `[n]` de la respuesta se comprueba contra su
      fuente; la respuesta muestra si está respaldada.
- [x] **Manual de uso como fuente RAG**: cómo cargar la documentación, cómo se recupera, cómo
      se citan las fuentes y cómo leer el distintivo de citas (`docs/MANUAL-RAG.md`).
- [ ] **F6 — HF Spaces (Gradio)**: **congelada**, decisión pendiente (ver §11 del plan).
