# FunshiEngineAI

Asistente con IA para el motor gráfico **FunshiEngine**: construye código básico y sugiere
documentación según las necesidades del usuario y su base de conocimientos, **sin inventar
información** — si no hay una referencia que lo respalde, lo dice.

## Qué estamos montando

- **`frontend/`** — Chat de escritorio (Jetpack Compose Desktop).
- **`backend/`** — RAG (Retrieval-Augmented Generation): indexa fuentes, trocea documentos en
  chunks, recupera los más relevantes (BM25) y arma el prompt con el system prompt editable.
- **`llm-api/`** — Fachada de LLM: descarga el modelo GGUF, levanta `llama-server` (llama.cpp)
  en local y expone un `LlmGateway` con streaming SSE compatible con OpenAI. También puede
  apuntar a cualquier API compatible (Ollama, LM Studio, vLLM, OpenAI…).

### Fuentes de conocimiento (RAG)

| Fuente | Estado | Descripción |
|--------|--------|-------------|
| Repos de GitHub | ✅ | `githubSources` en la config descarga el zipball y indexa `.md`, `.txt`, `.rst`, `.adoc`… |
| System prompt | ✅ | `config/system_prompt.md` — editable sin reiniciar, define el rol y las reglas. |
| Docs internas | ✅ | `internal-docs/` — carpeta local de documentos. |
| Manual del FunshiEngine | 📋 futuro | Un `.md` de documentación del motor, pensado como **manual de uso para personas que
hacen juegos** (no para quienes tocan el código del motor): mezcla de guía, recetas y
referencia de API. |

### Capacidades del asistente

- Construir **código básico** (escenas, entidades, shaders sencillos, scripts) usando como
  referencia la documentación indexada.
- **Sugerir documentación** según la necesidad del usuario (qué falta, qué conviene escribir).
- Responder preguntas citando las fuentes con números (`[1]`, `[2]`).

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
    "chatTimeoutMs": 0,             // 0 = sin límite de tiempo por respuesta
    "loadTimeoutMs": 180000,        // espera máxima a llama-server
    "llamaServerPath": "",          // vacío = autodetecta (clon compilado → PATH)
    "llamaRepoUrl": "https://github.com/ggml-org/llama.cpp.git",
    "llamaRepoVersion": "",         // rama/tag (vacío = HEAD)
    "llamaRepoDir": "",             // vacío = <app-home>/llama.cpp
    "gitBin": "git",
    "buildFromSource": false,       // true = clona y compila llama-server con cmake
    "localPort": 8081,              // si está ocupado, prueba los 19 siguientes
    "modelsDir": "models",
    "hfToken": "",                  // solo si el repo de HF lo exige
    "modelCatalog": [ … ],          // catálogo de modelos (pantalla Modelos)
    "apiPresets": [ … ]             // colección de APIs (pantalla APIs)
  },
```

El front tiene 4 pantallas (pestañas **Chat / Modelos / APIs / Estado**):

- **Estado** (carga): clonar llama.cpp con `%`, compilar con cmake con `%`,
  descargar el GGUF con MB y `%`, arrancar el servidor — con botón Reintentar.
- **Modelos**: catálogo con Descargar (barra +Cancelar), Activar (reinicia el
  servidor con ese modelo), agregar repo/archivo de HF (con "Listar .gguf").
- **APIs**: colección de endpoints OpenAI-compatibles (Ollama/LM Studio de
  ejemplo), Activar (modo REMOTO) y "Volver a modelo local".
  "rag": {
    "githubSources": [ { "owner": "…", "repo": "…", "ref": "", "paths": ["docs/"] } ],
    "internalDocsDirs": ["internal-docs"],
    "systemPromptFile": "config/system_prompt.md",
    "topK": 5
  }
}
```

**Modelo local por defecto**: `Phi-4-mini-instruct-Q4_K_M.gguf`
(`https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf`,
~2.4 GB) — rápido y bueno en español; se descarga solo al primer arranque, con reanudación si se corta.

**Requisitos**: JDK 21, Gradle, y `llama-server` de llama.cpp en el PATH (o indicar la ruta en
`llm.llamaServerPath`).

## Ejecutar

```bash
gradle :frontend:run
```

En desarrollo todo (config, modelos, docs internas) vive en `<raíz>/app-home`.

## Estado / roadmap

- [x] Chat local con streaming + RAG (BM25) sobre GitHub y docs internas.
- [x] Descarga del modelo con reanudación y arranque de `llama-server`.
- [x] Validación estricta de config, errores tipados y robustez del servidor local.
- [ ] Manual de uso del FunshiEngine como fuente RAG.
- [ ] Verificador determinista de citas (script post-generación).
- [ ] Fallback local↔remoto con circuit breaker si se habilita modo dual.
- [ ] **Plan de integración en curso** (`docs/PLAN-INTEGRACION.md`): tool
      calling + AgentLoop, render markdown/código con extensiones reales,
      archivos descargables en chat, web (DuckDuckGo), LaTeX (math + PDF),
      HF Spaces (Gradio) y arquitectura MCP preparada.
