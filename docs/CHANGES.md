# Registro de Cambios - FunshiEngineAI

## v0.5.0 (en desarrollo) — F1: núcleo de tool calling

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