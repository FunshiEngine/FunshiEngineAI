# Lista de tareas - FunshiEngineAI

Lista de pendientes y mejoras planificadas. Cada tarea se marca al implementarse
y se mueve a `docs/CHANGES.md` en el próximo registro de versión.

---

## Pendientes

### 1. Modelos: "Ver archivos" inline bajo cada resultado (con "Ocultar archivos")

**Estado:** IMPLEMENTADO (2026-10-18) — compilado.

Implementación:

- Estado por resultado: `expandedRepos: Set<String>` (varios expandidos a la vez),
  `files: Map<String, List<HfGgufFile>>` y `listing: Set<String>` (carga por repo,
  sin pisarse).
- `pickRepo(id)` ahora es toggle: colapsa si está abierto; si no, lo abre, marca
  `repo = id` y le pide los `.gguf` sólo si faltan (no re-pide al recolapsar).
- Composable `RepoFilesList(files, loading, selectedFile, onSelect)` renderizado
  **dentro** de `results.forEach`, envuelto en `AnimatedVisibility` con la fila;
  etiqueta alterna "Ver archivos" / "Ocultar archivos".
- El ✔ sólo se pinta dentro del repo elegido (`repo == hit.id`).
- "Repo elegido" abajo queda como resumen (repo + archivo), sin duplicar la lista.
- `Modifier.animateContentSize()` para que el despliegue no salte.

**Problema actual**

- El listado de `.gguf` se renderiza una sola vez, en el bloque
  `if (repo.isNotBlank())` que queda *después* del `results.forEach` (línea ~116),
  o sea "abajo del todo" de la tarjeta del buscador, lejos del resultado que se
  clickeó.
- El botón siempre dice "Ver archivos": no hay estado de expandido/oculto, así
  que no se puede cerrar la lista sin elegir otro repo.
- `files` / `listing` son estado único del pane (no por repo), pensados para un
  solo repo abierto a la vez.

**Solución propuesta (acordeón por resultado, VARIOS abiertos a la vez)**

1. Estado nuevo: `var expandedRepos by remember { mutableStateOf(setOf<String>()) }`
   (varios resultados pueden estar abiertos en simultáneo).
2. `files` pasa de `List<HfGgufFile>` a `Map<String, List<HfGgufFile>>` (clave =
   id del repo) y `listing` a `Set<String>` (repos que se están listando), así
   cada resultado carga y muestra sus propios archivos sin pisar a los demás.
   `vm.listRepoFiles(id)` responde por callback: se guarda en el map de ese repo.
3. `pickRepo(id)` pasa a ser *toggle*:
   - si `id ∈ expandedRepos` → lo quita del set (colapsa);
   - si no → lo agrega al set, setea `repo = id`, limpia `file` si cambió de
     repo y dispara `vm.listRepoFiles(id)` sólo si ese repo aún no tiene
     archivos cargados (evita re-pedir al recolapsar).
4. Extraer el render de la lista de archivos a un composable privado
   `RepoFilesList(files, listing, selectedFile, onPickFile)` y llamarlo **dentro
   del `results.forEach { hit -> ... }`**, justo debajo de la fila, envuelto en
   `AnimatedVisibility(hit.id in expandedRepos)` para que el despliegue sea
   instantáneo y "dinámico".
5. Etiqueta condicional en el botón y en la fila clickeable:
   `Text(if (hit.id in expandedRepos) "Ocultar archivos" else "Ver archivos")`.
6. El botón superior "Listar .gguf del repo" (y el alta manual) también deben
   abrir el acordeón: `expandedRepos = expandedRepos + repo`.
7. Detalle de UX: como la expansión ocurre *debajo* de la fila clickeada, la
   fila no se mueve de lugar, por lo que no hace falta auto-scroll.

**Efectos/riesgos a tener en cuenta**

- `file` (selección con ✔) es global y sólo se limpia al cambiar `repo`: con
  varios acordeones abiertos el ✔ sólo debe pintarse dentro del repo elegido
  (comparar `repo == hit.id` antes de marcar).
- La selección de un archivo debe mantener el acordeón abierto para que se vea
  el ✔.
- La tarjeta del buscador crece en altura (`verticalScroll` del pane): usar
  `Modifier.animateContentSize()` en la Columna de la tarjeta para que no salte.

**Criterios de aceptación**

- [x] Al pulsar "Ver archivos" en un resultado, los `.gguf` aparecen justo debajo
      de esa fila.
- [x] El botón alterna a "Ocultar archivos" y la lista se cierra.
- [x] Varios resultados pueden estar expandidos a la vez, cada uno con sus
      propios archivos (sin pisarse).
- [x] El archivo elegido se ve marcado dentro de la lista inline del repo elegido.
- [x] "Repo elegido" abajo no duplica la lista.

---

### 2. Modo "Docs" híbrido

**Estado:** DESCARTADO por el usuario (2026-10-18) — "el chat me gusta como está".
Queda anotado por si en el futuro cambia la opinión. Idea original: en modo DOCS,
si el contexto de la documentación no cubre la pregunta, caer al conocimiento
general del modelo con una nota, en vez de responder "No encuentro eso en la
documentación".

---

### 3. Refactor: sistema de chats con carpetas (árbol, drag & drop, click derecho)

**Estado:** IMPLEMENTADO (2026-10-18) — compilado y en prueba en runtime.

Implementación:

- `estructuras/ArbolEnlazado`: nuevo método `move(p, newParent, index)` con
  validación de ciclos (no mover un nodo dentro de su propio subárbol).
- `backend/rag/ChatStore`: esquema recursivo `StoredNode` (carpeta con hijos /
  chat con mensajes) con **migración automática** del formato v1 plano.
- `frontend/app/ChatTree.kt`: `ChatNode` (Folder/Chat), `ChatRow` (fila
  aplanada) y `ChatTree` (árbol + índice id→posición + serialize/deserialize).
- `ChatViewModel`: `newChat(parentId)`, `newFolder(parentId)`, `renameNode`,
  `deleteNode` (recursivo), `moveNode`, `dropOnto`, `toggleFolder`,
  `subtreeCounts`; `chatRows`/`expandedIds` como estado de UI. El chat activo
  se salva del subárbol borrado; siempre queda al menos un chat.
- `ChatPane`: sidebar sin botón "+ Nuevo chat"; carpetas con ▸/▾ e indentación
  por profundidad; **click derecho** sobre items (renombrar/borrar/crear dentro)
  y sobre el fondo (crear en la raíz); **diálogo de renombrar**; **confirmación
  de borrado** con conteo de chats/carpetas; **drag & drop manual**
  (`detectDragGestures` + hit-testing de límites por fila, con fantasma que
  sigue al puntero) — la API `dragAndDropSource/Target` de Compose 1.7.3 no
  existe aún, se implementó a mano.
- Nota Compose 1.7.3: no hay `onPreviewPointerEvent`, ni `MimeType`, ni
  `PointerInputChange.button` (el botón está en `PointerEvent.button`).

Puntos ya definidos con el usuario:

- Usar las estructuras del módulo `estructuras` (`ArbolEnlazado<ChatNode>`) como
  modelo en memoria del árbol chat/carpeta.
- Carpetas anidables; un chat es siempre hijo de una carpeta (o de la raíz).
- Arrastrar chats y carpetas desde la GUI para ordenarlos y meterlos en carpetas.
- Carpetas desplegables (expand/collapse).
- Renombrar chats y carpetas.
- Menú de click derecho sobre la barra de chats: crear chat, crear carpeta,
  renombrar, borrar.
- Eliminar el botón "+ Nuevo chat".
- Persistencia anidada (nuevo esquema en `config/chats.json` con migración del
  formato plano viejo).

Decisiones de UX confirmadas (2026-10-18):

- **Borrar carpeta con contenido:** confirmación ("Se eliminarán N chats y
  M carpetas, ¿continuar?") y borrado recursivo del subárbol.
- **Click derecho:** funciona sobre items y sobre la zona vacía de la barra.
  Sobre chat → renombrar, borrar. Sobre carpeta → renombrar, borrar, crear
  chat/carpeta dentro. Sobre el fondo vacío → crear chat o carpeta en la raíz.
- **Orden:** primero terminar este refactor; la mejora "Ver archivos"
  (tarea 1) se implementa después.

Nota sobre persistencia de chats: **ya existe** (`ChatStore` guarda en
`config/chats.json` tras cada cambio). Este refactor la extiende al nuevo
esquema anidado; no hay que crear el sistema desde cero.

---

### 4. Motor de inferencia: botón "Documentación" por API

**Estado:** IMPLEMENTADO (2026-10-18) — compilado. Decisión: **documentación POR
MOTOR** (cada API y el modelo local tienen su propia carpeta).

Implementación:

- Carpeta por motor: `app-home/api-docs/<id>/` (`"local"` para el modelo local).
  Los archivos se **copian** ahí desde el selector del SO, así la persistencia no
  depende de rutas temporales; no hace falta campo en `ApiPreset`.
- `KnowledgeBase.motorDocsDir`: carpeta opcional indexada además de las globales
  (GitHub + `internal-docs`), con etiqueta de citación `motor/<id>`.
- `ChatViewModel`: `activeMotorId()`, `docsDirFor()`, `listDocs()` (nombre +
  extensión + peso), `addDocsFiles()` (copia sin pisar nombres: sufijo `-2`,
  `-3`…), `removeDocsFile()`; `boot()`/`reindex()` setean `kb.motorDocsDir`
  antes del `sync` → **al activar otra API se reindexa con sus docs**.
- Reindexado automático tras agregar/quitar sólo si el motor es el activo (los
  demás se indexan al activarse).
- UI (`ApisScreen.kt`): botón **"Documentación"** en cada tarjeta de API y en la
  tarjeta del modelo local. Abre `DocsPanel` (AlertDialog) con el listado
  (nombre completo, "Extensión: .md · 12 KB"), botón "Eliminar" por archivo y
  "Agregar archivos desde el sistema…" (JFileChooser en el hilo de Swing,
  filtro a formatos de texto: md/txt/rst/adoc/mdx — los únicos que el indexer
  de texto sabe leer; PDF/binarios quedan fuera a propósito).

Criterios de aceptación:

- [x] Botón "Documentación" en Motor de inferencia, por API y en local.
- [x] Panel con archivos cargados (nombre + extensión).
- [x] Agregar archivos desde el sistema (se copian al motor y se indexan).
- [x] Eliminar archivos (se borran y se reindexa si el motor está activo).
- [x] Persistencia al reinicio (carpeta propia en `app-home/api-docs/`).
