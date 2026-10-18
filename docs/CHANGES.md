# Registro de Cambios - FunshiEngineAI

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
- Validación fail-fast estilo SAI (`LlmConfig.validate()`)

---

## Próximos Pasos (Roadmap)

- [ ] Tests unitarios para `ChatViewModel` (cola mensajes, boot, cancel)
- [ ] Tests de integración para `KnowledgeBase.sync()` + `RagEngine.ask()`
- [ ] Soporte multi-ventana / pestañas de chat
- [ ] Exportar/importar conversaciones (JSON/Markdown)
- [ ] Plugin system para fuentes RAG personalizadas
- [ ] Métricas de latencia / tokens por segundo en UI