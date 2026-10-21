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

Sin argumentos: `test` corre los tests de los cinco módulos (`llm-api`,
`voice-api`, `backend`, `frontend`, `estructuras`).

## Dónde viven

| Módulo     | Ruta                          | Qué se testea                        |
|------------|-------------------------------|--------------------------------------|
| `llm-api`  | `llm-api/src/test/kotlin`     | Config del LLM, validación fail-fast |
| `voice-api` | `voice-api/src/test/kotlin`   | Audio, silencio, config y sesión de voz |
| `backend`  | `backend/src/test/kotlin`     | Tools, permisos, sanitizador, MCP, config |
| `frontend` | `frontend/src/test/kotlin`    | Markdown, render de math, voz del chat |
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
- `McpStdioClientTest` — el cliente MCP por stdio contra un servidor **falso
  en memoria**: handshake, versión negociada, lista de herramientas, llamada,
  contenido binario, `isError`, error del protocolo, timeout, ruido en stdout,
  notificación del servidor, petición del servidor al cliente y cierre.
- `McpStdioProcessTest` — el mismo protocolo contra un **proceso real**: la
  misma JVM del test hace de servidor MCP (`FakeMcpServer`), así que se
  prueban el pipe, el handshake, el cierre del proceso y el log a stderr. Sin
  red y sin instalar nada.
- `ConfigMigrationTest` — la migración v3 → v5 tiene que ser invisible: una
  config vieja se comporta exactamente igual que antes.
- `WavCodecTest` — el WAV PCM 16 se codifica y se decodifica; un archivo con
  otro formato se rechaza con un error explícito.
- `AudioResamplerTest` — el downmix a mono promedia los canales y el remuestreo
  a 16 kHz baja (o sube) la cantidad de muestras sin tocar un audio que ya está
  en el formato pedido.
- `SilenceDetectorTest` — el silencio cierra la frase, el ruido corto no cuenta
  como frase y el detector se reinicia como nuevo.
- `VoiceConfigTest` — la validación fail-fast: falta de URL, de clave, de modelo
  de whisper, umbrales fuera de rango y topes incoherentes.
- `VoiceServiceTest` — cada motor se elige por la config, y un motor mal
  configurado falla rápido en vez de devolver nada.
- `VoiceSessionTest` — el ciclo escuchar → transcribir → hablar con dobles: la
  voz escuchada llega como texto, el micrófono se cierra al terminar, la
  grabación se corta al tope y un fallo del motor vuelve como error.
- `ChatVoiceTest` — el texto que se va a leer en voz alta se limpia (markdown,
  código, enlaces) y lo que se dicta acaba en el redactor.
- `ScrollbarKeysTest` — el scrollbar con teclado: las flechas mueven una
  línea, las páginas una pantalla, inicio/fin van a los extremos y nunca se
  pasa del tope.
- `ResolvedVoiceTest` — la voz apagada no hereda nada; si la URL o la clave
  están vacías, se heredan del motor de inferencia activo; un endpoint local no
  pide clave.
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

376 tests (`llm-api` 32, `voice-api` 47, `backend` 244, `frontend` 53).
