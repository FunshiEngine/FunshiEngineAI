# Registro de Cambios - FunshiEngineAI

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