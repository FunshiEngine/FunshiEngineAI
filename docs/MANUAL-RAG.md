# Manual de uso del FunshiEngineAI como fuente RAG

Guía para **alimentar el motor con documentación** y obtener respuestas que se puedan
verificar. Está escrito para quien usa la app (o la configura), no para quien modifica
el código: la parte técnica está en [`ARCHITECTURE.md`](ARCHITECTURE.md).

---

## 1. Qué es "usar el FunshiEngineAI como fuente RAG"

Es poner tus documentos en la base de conocimiento y preguntar sobre ellos. La app
recupera los fragmentos relevantes, se los pasa al modelo **con números de cita**
(`[1]`, `[2]`…) y después **comprueba** que lo que respondió esté respaldado por esas
fuentes.

La diferencia con un chat común es que la respuesta tiene que estar en la documentación
que le diste. Si no está, el modelo lo dice ("No encuentro eso en la documentación") y
el distintivo de citas te muestra si lo que respondió se sostiene o no.

Flujo completo:

```
tus documentos  ──►  índice (fragmentos)  ──►  contexto [1] [2] [3]  ──►  respuesta
                                                                          │
                                                        verificación ◄─────┘
                                                    ("Citas verificadas (3)")
```

---

## 2. Los dos modos del chat

El modo se cambia con las pestañas **General / Docs** sobre el chat (queda guardado entre
sesiones).

| Modo | Qué hace | ¿Citas? | ¿Verificación? |
|------|----------|----------|----------------|
| **General** | Conversación libre. El modelo usa su propio conocimiento. | No | No |
| **Docs** | El modelo responde **sólo** con lo que recuperó de tu documentación. | Sí (`[1]`, `[2]`) | Sí (distintivo) |

**Regla práctica**: si estás preguntando por *tu* proyecto, usá **Docs**. Si estás
pidiendo una receta general, una idea o cómo escribir código ("¿cómo hago un singleton
en Kotlin?"), usá **General**: en Docs la respuesta se limita a lo que hay indexado.

El prompt de Docs es el de `config/system_prompt.md` (editable sin reiniciar la app).
Le dice al modelo que responda sólo con el contexto, que cite con `[n]` y que no invente.
El verificador (sección 6) es la parte programática de esa instrucción.

---

## 3. De dónde sale el conocimiento

Hay cuatro fuentes. Todas se indexan juntas y sus citas se distinguen por la etiqueta.

| Fuente | Dónde está | Etiqueta de la cita |
|--------|-------------|---------------------|
| Repos de GitHub | `rag.githubSources` en la config | `owner/repo: ruta/archivo.md` |
| Documentación interna | `<app-home>/internal-docs/` | `interno/internal-docs: archivo.md` |
| Documentación por motor | `<app-home>/api-docs/<id-motor>/` | `motor/<id-motor>: archivo.md` |
| Prompt del sistema | `<app-home>/config/system_prompt.md` | (no se cita: es el prompt) |

`<app-home>` es `app-home/` en el repo durante el desarrollo y `~/.docs-chatbot` en una
instalación normal. La app lo crea sola en el primer arranque.

### 3.1 Documentación interna (la más directa)

Copiá tus archivos en `app-home/internal-docs/` y tocá **Reindexar** (botón arriba a la
izquierda del chat). También podés usar el panel **Documentación** de cada API: ahí los
archivos se **copian** a la carpeta del motor y se indexan solos.

Formatos que el indexador de texto sabe leer: `.md`, `.mdx`, `.txt`, `.rst`, `.adoc`.
**Los PDF y binarios no se indexan** (a propósito: el indexer es de texto plano). Para un
PDF usá la herramienta `web_fetch`/`download_url` o el panel Documentación sólo con
`.md`.

### 3.2 Documentación por motor

Cada API y el modelo local tienen su propia carpeta (`api-docs/<id>/`). Sirve para
documentación que sólo le sirve a **ese** motor (por ejemplo, las reglas de un servicio
remoto). Al activar otro motor se reindexa con sus docs.

### 3.3 Repos de GitHub

En `app-config.json`, sección `rag.githubSources`:

```json
"rag": {
  "githubSources": [
    { "owner": "FunshiEngine", "repo": "FunshiEngineAI", "ref": "", "paths": ["docs/"] }
  ]
}
```

- `ref` vacío = rama por defecto; también acepta tag o SHA.
- `paths` filtra por carpeta (ej. `["docs/", "guides/"]`). Vacío = todo el repo.
- Para repos privados: `token` en la config o la variable de entorno `GITHUB_TOKEN`.
- El zip se descarga a `<app-home>/data/cache/`; **sin red, la app sigue con la caché**.
- Se indexan los archivos con extensión permitida de menos de 1 MB, ignorando
  `node_modules/`.

---

## 4. Cómo se indexa (y cómo escribir para que se encuentre)

1. Se leen los documentos y se **trocean** en fragmentos: primero por encabezados
   Markdown (`#`, `##`…) y después por tamaño, con solapamiento.
   - `chunkSize`: 1200 caracteres (configurable en `rag.chunkSize`).
   - `chunkOverlap`: 150 caracteres (configurable en `rag.chunkOverlap`).
   - Cada fragmento conserva el **encabezado** de su sección: es lo que se muestra en la
     cita.
2. Los fragmentos se indexan con **BM25** (sin embeddings, sin modelo): la recuperación
   es por coincidencia de términos, con normalización (minúsculas, sin acentos, camel
   case partido) y una lista corta de palabras vacías.
3. Cada pregunta recupera los `topK` fragmentos (5 por defecto) **más relevantes**.

### Consejos para que el recuperación funcione

- **Usá los términos que van a aparecer en la pregunta.** BM25 no entiende sinónimos:
  si tus docs dicen "escena" y la pregunta dice "nivel", no hay coincidencia.
- **Una sección por concepto, con título claro.** El título del encabezado viaja a la
  cita: `"Configuración — Instalar el motor"` se lee mucho mejor que `"manual.md"`.
- **Fragmentos de 800–1500 caracteres.** Si una sección es enorme, el contexto se
  diluye y la respuesta pierde detalle.
- **Repetí el término clave en el título y en el texto.** Un título como "Permisos de
  herramientas" hace que la pregunta "permisos" la encuentre.
- **Los bloques de código se indexan enteros**, pero cortá los ejemplos largos: el
  modelo no puede citar un bloque de 300 líneas y verificarla igual.

---

## 5. Cómo se responde y cómo se citan las fuentes

En Docs, la app arma el mensaje así:

```
CONTEXTO:
[1] (owner/repo — Título de la sección)
<fragmento 1>

---

[2] (interno/internal-docs — Otra sección)
<fragmento 2>

PREGUNTA:
<tu pregunta>
```

El modelo tiene que responder citando con `[n]`. El `n` es **el orden del fragmento
en el contexto**, y la app lo traduce a la fuente real: al final de la respuesta se ve la
lista

```
Fuentes:
[1] owner/repo: docs/motor.md
[2] interno/internal-docs: guia.md
```

Si el modelo se olvida de citar, la respuesta aparece **sin fuentes** y el distintivo de
citas no aparece: es la señal de que la respuesta no vino de tu documentación.

---

## 6. La verificación de citas (lo nuevo de F9)

Después de cada respuesta en modo Docs, la app comprueba **cada cita `[n]`** contra la
fuente `n`. Es determinista: no llama al modelo ni a la red, así que nunca falla por
culpa de la conexión y siempre da el mismo resultado.

Una cita está **respaldada** si cumple alguna de estas tres reglas:

| Regla | Qué exige |
|-------|-----------|
| **Literal** | El fragmento citado aparece en la fuente (tras normalizar: minúsculas, sin acentos, sin puntuación). |
| **Sin detalles inventados** | Los nombres propios y los números (2+ dígitos o con decimal) que dice el fragmento aparecen en la fuente. |
| **Paráfrasis** | Al menos el 50% de las palabras significativas del fragmento aparecen en la fuente (acepta plurales y variaciones de verbo: "renderiza" ≈ "renderizar"). |

La segunda regla es la que separa una paráfrasis de una invención: si el modelo escribió
"usa DirectX 12" y la fuente dice "usa Vulkan", la cita **no** está respaldada aunque las
frases se parezcan.

### Qué se ve en el chat

| Distintivo | Qué significa |
|------------|---------------|
| **Citas verificadas (n)** | Todas las citas de la respuesta están respaldadas por su fuente. |
| **k citas sin respaldo** | k citas citan una fuente que existe pero no respalda lo que se dice. |
| **· m sin fuente** | m citas apuntan a un `[n]` que no existe (el modelo inventó el número). |

El resultado se guarda con el mensaje: el distintivo sigue ahí al reabrir el chat.

### Qué hacer si una cita sale sin respaldo

1. **Abrí la fuente** desde la lista de fuentes: si el contenido está pero está en otro
   lugar del documento, es un problema de troceado (subí `chunkSize` o reorganizá los
   encabezados).
2. Si el contenido **no** está en la fuente, el modelo inventó: es el comportamiento que
   querés ver marcado, no un bug de la app.
3. Si el contenido está pero el modelo lo **exageró** (números, nombres), ajustá el
   `system_prompt.md` para ser más explícito con los números y los nombres propios.

---

## 7. Herramientas en modo Docs

El modelo puede usar herramientas además de responder. Todas piden permiso (por
categoría y **por chat**, apagado por defecto):

| Tool | Categoría | Qué hace |
|------|-----------|----------|
| `web_search` | WEB | Busca en DuckDuckGo (con respaldo en Wikipedia). |
| `web_fetch` | WEB | Trae una página y la devuelve como texto. |
| `download_url` | FILES | Baja un archivo a la carpeta del chat (http/https, 50 MB máx.). |
| `create_file` | FILES | Escribe un archivo en la carpeta del chat (5 MB máx.). |
| `latex_compile` | FILES | Compila un `.tex` a PDF (necesita `pdflatex` o `xelatex`). |
| `get_time` | — | La hora actual (sin permiso). |

Para usar las que necesitan permiso, en el chat:

```
/permitir-web      # concede la categoría WEB a este chat
/permitir-archivos # concede FILES
/permitir-mcp      # concede MCP
/permisos          # estado
/revocar-web       # quita el permiso
```

El permiso es **por chat**: no afecta a los demás chats y hay que concederlo de nuevo
en cada chat nuevo.

> **Nota**: las herramientas-web y las de archivos no son RAG: no se indexan. Son
> herramientas de apoyo para resolver lo que la documentación no cubre.

---

## 8. Configuración de referencia

Todo vive en `<app-home>/config/app-config.json` (se crea solo en el primer arranque).
Lo que afecta al RAG:

```json
{
  "rag": {
    "githubSources": [
      { "owner": "FunshiEngine", "repo": "FunshiEngineAI", "ref": "", "paths": [], "token": "" }
    ],
    "internalDocsDirs": ["internal-docs"],
    "extensions": ["md", "mdx", "txt", "rst", "adoc"],
    "systemPromptFile": "config/system_prompt.md",
    "cacheDir": "data/cache",
    "chunkSize": 1200,
    "chunkOverlap": 150,
    "topK": 5,
    "historyTurns": 6
  }
}
```

| Clave | Qué hace al cambiarla |
|-------|-----------------------|
| `extensions` | Qué formatos se indexan. Si agregás uno, recordá que el indexer es de texto plano. |
| `chunkSize` / `chunkOverlap` | Tamaño de los fragmentos. Subí `chunkSize` si las secciones son largas. |
| `topK` | Cuántos fragmentos van al contexto. Subilo (8–10) si las respuestas se quedan cortas; bajalo si el modelo se confunde con demasiado contexto. |
| `historyTurns` | Cuántos turnos de conversación se conservan. |
| `systemPromptFile` | Ruta del prompt. **Se relee en cada pregunta**: lo editás y aplica en la siguiente. |

Secciones vecinas (no son RAG pero conviven): `tools` (interruptor y timeout), `outputs`
(carpetas y topes), `latex` (motor y timeout) y `mcp` (servidores declarados).

---

## 9. Recetas

### "Quiero preguntarle a la app por mi proyecto"

1. Copiá la documentación a `app-home/internal-docs/` (o configurá el repo en
   `githubSources`).
2. **Reindexar**.
3. Cambiá a **Docs** y preguntá con las palabras que usás en los docs.

### "El modelo no encuentra lo que le pregunto"

1. ¿Usás **Docs**? En General no hay recuperación.
2. ¿Usaste las palabras del documento? BM25 no entiende sinónimos: probá con el
   término exacto.
3. ¿El documento tiene una extensión indexable y es texto plano?
4. ¿Reindexaste después de copiarlo?
5. Mirá la etiqueta de la cita: si dice `interno/internal-docs`, la carpeta es la
   correcta; si dice `motor/<id>`, estás viendo las docs de otro motor.

### "Quiero que responda sólo con mis docs"

Editá `config/system_prompt.md` y dejá explícita la regla de citar. El archivo se relee en
cada pregunta, no hace falta reiniciar.

### "Quiero que traiga información de la web"

El modelo puede usar `web_search`/`web_fetch` si le concedés el permiso WEB. El
resultado de la web **no** es una fuente citable (no está indexada): el modelo la usa
para responder, y las citas `[n]` siguen apuntando a tu documentación.

### "Quiero un PDF con la respuesta"

Pedile al modelo que use `latex_compile` (categoría FILES). El PDF aparece como adjunto
en el mensaje. **Requiere** `pdflatex` o `xelatex` instalado (TeX Live o MiKTeX); si no
está, la tool lo dice con la guía de instalación en vez de devolver un archivo vacío.

---

## 10. Problemas frecuentes

| Síntoma | Causa probable | Qué hacer |
|---------|----------------|-----------|
| "No encuentro eso en la documentación" | La recuperación no trajo nada relevante | Usá los términos del documento; subí `topK`; revisá `chunkSize`. |
| El distintivo de citas no aparece | El modelo no cita | Reforzá la regla 3 del `system_prompt.md`. |
| "k citas sin respaldo" | El modelo inventó un detalle (número o nombre) | Es el comportamiento esperado: marca la invención. Revisá la fuente citada. |
| "m sin fuente" | El modelo citó un `[n]` inexistente | Mismo caso anterior, más grave. |
| El índice está viejo | No reindexaste | Botón **Reindexar**. |
| GitHub no baja nada | Repo privado sin token, o red caída | `GITHUB_TOKEN` en la config o en el entorno; sin red se usa la caché. |
| `latex_compile` falla | No hay motor LaTeX instalado | Instalá TeX Live o MiKTeX (la tool devuelve la guía). |
| El modelo responde en otro idioma | No es un bug: el prompt pide el mismo idioma de la pregunta | — |

---

## 11. Límites conocidos

- **Sin embeddings**: la recuperación es léxica (BM25). No entiende parafraseos ni
  sinónimos; sí funciona bien con los términos técnicos exactos.
- **Sin OCR ni PDF**: el indexer lee texto plano. Los PDF hay que convertirlos (o
  bajar el texto y guardarlo como `.md`).
- **La verificación no corrige**: informa. No regenera la respuesta ni la bloquea.
- **Sin sinónimos ni expansión de consulta**: `chunkSize` y `topK` son las dos palancas.
- **Sin multi-idioma en la cita**: la normalización quita acentos, así que un documento en
  español y una pregunta en español funcionan bien; mezclarlos es lo normal y funciona.

---

## 12. Referencias

| Documento | Qué cubre |
|-----------|-----------|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Cómo está hecho por dentro (paquetes, RAG, tool calling, render). |
| [`PLAN-INTEGRACION.md`](PLAN-INTEGRACION.md) | Estado de cada fase y criterios de aceptación. |
| [`TESTING.md`](TESTING.md) | Los tests y cómo correrlos. |
| [`CHANGES.md`](CHANGES.md) | Historial de cambios por versión. |
