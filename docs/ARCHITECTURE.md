# Arquitectura de FunshiEngineAI

## Visión General

FunshiEngineAI es una aplicación de escritorio (Compose Multiplatform / Desktop) para chat RAG (Retrieval-Augmented Generation) con modelos LLM locales o remotos. La arquitectura sigue principios de **separación de responsabilidades**, **reactividad** y **fail-fast**.

---

## Módulos del Proyecto

```
FunshiEngineAI/
├── frontend/          # UI Compose Desktop (Kotlin)
├── backend/           # Motor RAG (indexación, retrieval, prompts)
├── llm-api/           # Capa de inferencia LLM (local + remoto)
└── app-home/          # Directorio de trabajo en tiempo de ejecución (config, modelos, logs, docs)
```

### `frontend/` — UI Reactiva
- **Framework**: Compose Desktop (Material 3) + Kotlin 2.1
- **Patrón**: MVVM simplificado — `ChatViewModel` expone `MutableState` observables
- **Navegación**: Sidebar colapsable (`NavigationRailPane`) + pantallas (`Chat`, `Modelos`, `Motor`, `Config`)
- **Estado**: `UiState.kt` define tipos compartidos (`Screen`, `UiMessage`, `UiProgress`, `AppTheme`)

### `backend/` — Motor RAG
- **Indexación**: BM25 sobre chunks de documentos (GitHub + carpetas locales)
- **Fuentes**: `GithubDocsSource` (API REST) + `LocalDocsSource` (FS)
- **Chunking**: Solapamiento configurable (`chunkSize`, `chunkOverlap`)
- **Prompt**: System prompt cargado desde `config/system_prompt.md`
- **Config**: `RagConfig` serializado en `app-config.json`

### `llm-api/` — Capa de Inferencia
- **Modos**: `LOCAL` (llama.cpp vía `llama-server`) | `REMOTE` (OpenAI-compatible)
- **Config por supplier**: `LlmService({ config.llm }, ...)` y `ModelManager({ config.llm }, ...)` reciben `() -> LlmConfig`; cada arranque lee la config actual (así activar una API/modelo surte efecto inmediato)
- **Gestión de modelos**: Catálogo GGUF de Hugging Face, descarga streaming + reanudación (`.part` + `Range`)
- **llama.cpp**: Clonado (`git clone --depth 1`), checkout, build opcional (`cmake`), autodetección binario
- **Servidor local**: `LocalServerManager` — escaneo de puertos libres (B-11), health polling, tail de logs en errores
- **Gateway**: `OpenAiCompatibleGateway` — streaming SSE, timeouts configurables
- **Validación fail-fast**: `LlmConfig.validate()` antes de tocar red/procesos
---

## Flujo de Datos Principal

```
Usuario escribe -> ChatViewModel.send()
    |- Si engine listo -> stream tokens -> UI actualiza en tiempo real
    |- Si engine NO listo -> encola en pendingMessages + muestra "..." en UI
                          |
              boot() en background (corrutina) — por fases con % en UiProgress
    |- Fase 1: KnowledgeBase.sync() -> indexa docs
    |- Fase 2: descarga modelo (%, MB/MB)
    |- Fase 3: clonar/compilar llama.cpp si falta (%, sólo LOCAL y sólo si no está)
    |- Fase 4: LlmService.start() -> LocalServerManager.start() -> llama-server en puerto libre
    |- RagEngine creado -> ready = true -> processPendingMessages()
```

---

## Hilos y Concurrencia

| Componente | Dispatcher / Thread | Responsabilidad |
|------------|---------------------|-----------------|
| ChatViewModel.scope | Dispatchers.Main (Swing) | Estado UI, corrutinas de vida larga |
| KnowledgeBase.sync | Dispatchers.IO | I/O disco + red (GitHub) |
| HuggingFaceDownloader | Dispatchers.IO | Streaming HTTP chunks -> archivo |
| LlamaManager | Dispatchers.IO | git clone, cmake build, procesos |
| LocalServerManager | Dispatchers.IO | ProcessBuilder, health polling |
| RagEngine.ask | Dispatchers.IO | HTTP streaming a gateway |
| ChatViewModel.testApi | Dispatchers.IO + withTimeout(20 s) | GET /models de prueba ("Probar conexión") |
| UI updates | Dispatchers.Main | MutableState -> recomposicion Compose |

Regla: Todo I/O bloqueante va a Dispatchers.IO. La UI nunca se bloquea -- boot() lanza en background, el chat es usable inmediatamente.

---

## Gestion de Estado (Frontend)

```kotlin
// ChatViewModel -- estado observable
var screen by mutableStateOf(Screen.CHAT)        // Pantalla actual
var messages by mutableStateOf(listOf<UiMessage>()) // Historial chat
var ready by mutableStateOf(false)               // Modelo listo?
var busy by mutableStateOf(false)                // Generando respuesta?
var progress by mutableStateOf<UiProgress?>(null) // Progreso descarga/arranque
var status by mutableStateOf("...")              // Texto de estado
var error by mutableStateOf("")                  // Error visible (banner)
```

Reactividad: Compose se suscribe automaticamente a estos MutableState. Cambios -> recomposicion granular.

---

## Configuracion Persistida

Archivo: <app-home>/config/app-config.json

```json
{
  "llm": {
    "configVersion": 2,
    "mode": "LOCAL",
    "hfRepo": "unsloth/Phi-4-mini-instruct-GGUF",
    "hfFile": "Phi-4-mini-instruct-Q4_K_M.gguf",
    "buildFromSource": true,
    "modelCatalog": [...],
    "apiPresets": [...],
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

- Carga: AppConfig.loadOrCreate() (crea defaults + system prompt + carpeta docs si primera vez)
- Persistencia: config.save() atomica (escribe JSON pretty-print)
- **Migracion v1 -> v2** (`configVersion`): en v1 `buildFromSource` era `false` por defecto y existian APIs localhost prefijadas que forzaban el REMOTE a un servidor local vacio; en v2 `buildFromSource=true` (clona/compila llama.cpp solo si falta el binario) y esas APIs se eliminan.

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

- retryable = true -> boton "Reintentar" en UI
- retryable = false -> requiere accion del usuario (config, instalar git/cmake)
- Logs: LocalServerManager guarda tail (ultimos 40 KB) en llama-server.log y lo adjunta a excepciones

---

## Pantallas y Componentes (Frontend)

| Archivo | Responsabilidad |
|---------|-----------------|
| Main.kt | Entry point, ventana, tema dinamico |
| ChatScreen.kt | Layout raiz: sidebar + contenido + banner carga |
| NavigationRailPane.kt | Sidebar colapsable (200/72dp), animado, etiqueta "Motor" |
| ChatPane.kt | UI chat: lista burbujas, input, botones cancelar/enviar |
| ModelsPane.kt | Catalogo modelos: descargar/activar (o "Descargar y usar"), agregar HF |
| ApisScreen.kt (ApisPane.kt) | "Motor": segmentado LOCAL/REMOTE, coleccion APIs (prellenado), probar conexion |
| SettingsPane.kt | Tema claro/oscuro, color acento, modo LOCAL/REMOTE |
| LoadingPane.kt | Pantalla carga completa (fallback, retry) |
| ModelLoadingBanner.kt | Banner superior no intrusivo durante carga background |

Los paneles largos (Motor, Config, Modelos) usan `verticalScroll` + `VerticalScrollbar`/`rememberScrollbarAdapter` (no `LazyColumn`) para conservar el scroll del mouse.

---

## Extensibilidad

1. Nuevas fuentes RAG: Implementar DocSource + agregar a KnowledgeBase.sync()
2. Nuevos proveedores LLM: Implementar LlmGateway + registrar en LlmService.start()
3. Nuevas pantallas: Anadir a Screen enum + case en ChatScreen.kt + item en NavigationRailPane
4. Config extra: Extender LlmConfig / RagConfig (serializacion automatica con @Serializable)

---

## Requisitos de Ejecucion

- JDK 21 (Eclipse Temurin recomendado)
- Git en PATH (para clonar llama.cpp)
- CMake 3.20+ (solo si llm.buildFromSource=true)
- Ollama / LM Studio (opcional, para modo REMOTE)
- Windows/Linux/macOS -- Compose Desktop genera instaladores nativos

---

## Directorio de Trabajo (app-home)

```
~/.docs-chatbot/  (o $DOCS_CHATBOT_HOME o -Dapp.home=...)
├── config/
│   ├── app-config.json
│   └── system_prompt.md
├── models/           # GGUF descargados
├── llama.cpp/        # Clon + build (si buildFromSource)
├── logs/
│   ├── llama-server.log
│   └── llama-build.log
├── data/
│   └── cache/        # Repos GitHub clonado para indexacion
└── internal-docs/    # Docs locales del usuario (.md, .txt, .rst, .adoc)
```