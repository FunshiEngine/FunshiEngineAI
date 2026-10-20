# Testing

Infraestructura de tests del proyecto: JUnit 5 sobre Gradle, un test por
comportamiento y cero dependencias de la red.

## Cómo correrlos

```bash
./gradlew test                 # todos los módulos
./gradlew :backend:test        # un módulo
./gradlew :backend:test --tests "rag.agent.ToolPermissionsTest"
./gradlew check                # test + verificaciones
```

Sin argumentos: `test` corre los tests de los cuatro módulos (`llm-api`,
`backend`, `frontend`, `estructuras`).

## Dónde viven

| Módulo     | Ruta                          | Qué se testea                        |
|------------|-------------------------------|--------------------------------------|
| `llm-api`  | `llm-api/src/test/kotlin`     | Config del LLM, validación fail-fast |
| `backend`  | `backend/src/test/kotlin`     | Tools, permisos, sanitizador, MCP, config |
| `frontend` | `frontend/src/test/kotlin`    | Markdown, render de math              |
| `estructuras` | `estructuras/src/test/kotlin` | (reservado)                       |

Cada módulo declara su propia configuración en su `build.gradle.kts` (misma
versión de JUnit, `useJUnitPlatform()`, `java.awt.headless=true`). No hay un
bloque mágico en el build raíz: el acceso tipado de `dependencies { }` sólo
existe dentro del build de cada módulo.

## Reglas

1. **Deterministas.** Un test no toca la red, no lee el reloj y no depende del
   disco del usuario (salvo temporales que se limpian al final). Lo que se
   pueda simular, se simula: la búsqueda del motor de LaTeX es un parámetro
   inyectable, el reloj del chat es un `TimeProvider`, etc.
2. **Un comportamiento por test.** El nombre del test dice qué tiene que
   pasar: ``fun `el nombre no puede traer rutas`()``. En español, porque el
   código y la documentación están en español.
3. **Fallar es información.** Si un test falla, el mensaje tiene que explicar
   qué se esperaba y qué se obtuvo. Un test que falla y no dice por qué es
   medio test.
4. **Sin red, sin esperas.** Nada de `Thread.sleep` ni de esperar a un
   servicio externo. Los timeouts se inyectan.

## Tests de ejemplo (por qué existen)

- `ToolPermissionsTest` — la puerta de permisos. Si deja pasar una herramienta
  sin permiso, la app escribe archivos o sale a internet sin consentimiento.
- `AgentLoopTest` — el bucle de tool calling: qué se ejecuta, qué vuelve al
  modelo, cuándo se corta (permiso denegado, errores seguidos, timeout) y
  qué pasa con la cancelación del usuario.
- `ToolCallSanitizerTest` — el modelo devuelve JSON roto; el sanitizador
  decide si la llamada se ejecuta o vuelve como feedback.
- `PromptToolParserTest` — el protocolo por prompt del modo LOCAL, incluido
  el stream partido en cualquier punto.
- `LatexToolTest` — `latex_compile` con un motor falso: no hace falta TeX
  instalado para testear el camino feliz y el de error.
- `FileToolsTest` — `create_file` sólo escribe dentro de la carpeta del chat.
- `WebToolsTest` — el HTML que se le pasa al modelo sale limpio.
- `ToolRegistryTest` — el registro distingue el origen de cada tool y puede
  reemplazarla (las tools MCP se redescubren en caliente).
- `McpToolAdapterTest` — una tool remota es una tool: nombre calificado,
  categoría MCP, binarios como adjuntos y errores que vuelven como error.
- `McpManagerTest` — el manager de servidores, con un conector falso (no
  hace falta ningún servidor MCP de verdad).
- `ConfigMigrationTest` — la migración v3 → v4 tiene que ser invisible: una
  config vieja se comporta exactamente igual que antes.
- `ChunkerTest` / `Bm25IndexTest` / `KnowledgeBaseTest` — el troceado, el
  orden de relevancia y la carga de la documentación.
- `RagEngineTest` — lo que ve el modelo: contexto numerado, historial
  recortado y el prompt de sistema.
- `ChatStoreTest` — los chats sobreviven al reinicio (árbol, adjuntos,
  permisos y migración del formato viejo).
- `CitationVerifierTest` — el verificador de citas: una cita literal, una
  paráfrasis, un nombre propio inventado, un número inventado, una fuente que
  no existe y una respuesta sin citas.
- `ChatMessagesTest` — lo que se ve en el chat es lo que se guarda (adjuntos,
  tarjetas de herramientas y verificación de citas).
- `MathRenderTest` / `MarkdownTest` — lo que el modelo escribe se ve bien.
- `LlmConfigTest` — la config rota se avisa antes de empezar a chatear.
- `OpenAiCompatibleGatewayTest` — el SSE se parsea bien, los `tool_calls`
  fragmentados se acumulan en una sola llamada y los errores HTTP se
  clasifican (401, 429, 400 por contexto, 500, servidor caído). Se prueba
  contra un **servidor HTTP local** del JDK: sin red y sin modelo.

## Cobertura actual

280 tests (`llm-api` 32, `backend` 211, `frontend` 37).
