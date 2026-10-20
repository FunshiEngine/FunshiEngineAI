package app.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chat.ChatViewModel
import app.chat.activateApi
import app.chat.addDocsFiles
import app.chat.configMode
import app.chat.listDocs
import app.chat.removeApi
import app.chat.removeDocsFile
import app.chat.saveApi
import app.chat.setConfigMode
import app.chat.testApi
import app.chat.useLocal
import app.core.Screen
import app.core.humanSize
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.launch
import llm.config.ApiPreset

/**
 * Plantilla de proveedor: los datos que NUNCA cambian (URL base) ya vienen
 * puestos; el usuario sólo completa lo que varía por cuenta (nombre, modelo,
 * API key). Si la plantilla se rompe (la API cambia de ruta), se puede
 * desactivar sin borrar el resto.
 */
private data class ApiProvider(
    val name: String,
    val baseUrl: String,
    val model: String,
    val needsKey: Boolean,
    /** false = la plantilla está rota y no se muestra (queda en el historial). */
    val enabled: Boolean = true,
)

// Plantillas pre-armadas con datos verificados contra la API real de cada
// proveedor. Orden: las nube primero (clave obligatoria) y locales al final.
private val API_PROVIDERS = listOf(
    ApiProvider(
        "Nvidia NIM", "https://integrate.api.nvidia.com/v1",
        "meta/llama-3.3-70b-instruct", needsKey = true,
    ),
    ApiProvider(
        "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
        // Verificado con curl: gemini-2.5-flash devuelve 404 para cuentas nuevas
        // ("no longer available to new users"); este es el modelo vigente.
        "gemini-3.8-flash", needsKey = true,
    ),
    ApiProvider(
        "OpenAI", "https://api.openai.com/v1",
        "gpt-4o-mini", needsKey = true,
    ),
    ApiProvider(
        "Grok (xAI)", "https://api.x.ai/v1",
        "grok-4", needsKey = true,
    ),
    ApiProvider(
        "Groq", "https://api.groq.com/openai/v1",
        "llama-3.3-70b-versatile", needsKey = true,
    ),
    ApiProvider(
        "OpenRouter", "https://openrouter.ai/api/v1",
        "openrouter/auto", needsKey = true,
    ),
    ApiProvider(
        "Ollama local", "http://localhost:11434/v1",
        "phi4-mini", needsKey = false,
    ),
    ApiProvider(
        "LM Studio local", "http://localhost:1234/v1",
        "local-model", needsKey = false,
    ),
)

/** Etiqueta del chip "a mano": permite editar URL/modelo libremente. */
private const val CUSTOM_PROVIDER = "Otro (a mano)"

/**
 * Gestor de APIs OpenAI-compatibles: es el equivalente a "Modelos" pero para
 * servicios con clave. Guarda VARIAS APIs (nombre + URL + clave + modelo),
 * activa la que quieras y avisa si el modo remoto no tiene ninguna configurada.
 *
 * Layout dinámico: 1) estado del motor, 2) selector LOCAL/REMOTO justo arriba de
 * 3) la configuración del modo elegido (colección + alta de APIs en REMOTE,
 * modelo local en LOCAL).
 */
@Composable
fun ApisPane(vm: ChatViewModel) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Prellenado con la API activa (o la primera guardada) SOLO la primera vez:
    // antes se re-sembraba con remember(apiPresets), y si el usuario ya estaba
    // tipeando la URL de Gemini la pisaba con la API vieja → "probar conexión"
    // terminaba contra la URL vieja y devolvía 404 engañoso.
    val seed = vm.apiPresets.firstOrNull { it.id == vm.activeApiId } ?: vm.apiPresets.firstOrNull()
    var label by remember { mutableStateOf(seed?.label ?: "") }
    var baseUrl by remember { mutableStateOf(seed?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(seed?.apiKey ?: "") }
    var model by remember { mutableStateOf(seed?.model ?: "") }
    // Proveedor elegido: si la semilla coincide con uno conocido, se marca ese.
    var provider by remember {
        mutableStateOf(
            when {
                seed == null -> ""
                else -> API_PROVIDERS.firstOrNull { it.baseUrl == seed.baseUrl }?.name ?: CUSTOM_PROVIDER
            },
        )
    }
    /** Id de la API en edición (null = alta nueva). */
    var editingId by remember { mutableStateOf<String?>(null) }

    /**
     * Panel "Documentación" abierto: id del motor (API o "local") cuyos
     * archivos se están administrando. null = panel cerrado.
     */
    var docsMotorId by remember { mutableStateOf<String?>(null) }
    /** Título del motor en el panel (para mostrar aunque el preset no esté en la lista). */
    var docsMotorTitle by remember { mutableStateOf("") }

    fun clearForm() {
        label = ""
        baseUrl = ""
        apiKey = ""
        model = ""
        provider = ""
        editingId = null
    }

    val remote = vm.configMode == "REMOTE"
    val activeApi = vm.apiPresets.firstOrNull { it.id == vm.activeApiId }
    // URL automática según proveedor: si hay, el campo URL base NO se muestra.
    val autoUrl = API_PROVIDERS.firstOrNull { it.name == provider }?.baseUrl ?: ""
    val urlLocked = autoUrl.isNotBlank()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // ---------- 1. Estado del motor ----------
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Motor de inferencia", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (remote) "Ahora mismo usa una API remota: no se descarga ningún modelo ni se arranca llama.cpp."
                        else "Ahora mismo usa el modelo local (llama.cpp).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (remote) "API activa: ${activeApi?.label ?: "ninguna"}"
                        else "Modelo local: ${vm.activeModelLabel}",
                        fontSize = 12.sp,
                    )
                }
            }

            // ---------- 2. Selección del motor (justo arriba de su configuración) ----------
            Text("¿Qué motor querés usar?", style = MaterialTheme.typography.titleSmall)
            // Segmentado LOCAL/REMOTO: lo que toca el usuario es lo que queda
            // escrito en el motor (setConfigMode valida que exista una API).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeSegment(
                    text = "Modelo local",
                    selected = !remote,
                    enabled = !vm.busy,
                    modifier = Modifier.weight(1f),
                    onClick = { if (remote) vm.useLocal() },
                )
                ModeSegment(
                    text = "API remota",
                    selected = remote,
                    enabled = !vm.busy,
                    modifier = Modifier.weight(1f),
                    onClick = { if (!remote) vm.setConfigMode("REMOTE") },
                )
            }

            // ---------- 3. Configuración del modo elegido ----------
            if (remote) {
                // APIs guardadas (varias, cada una con su clave)
                Text("Tus APIs (${vm.apiPresets.size})", style = MaterialTheme.typography.titleMedium)
                if (vm.apiPresets.isEmpty()) {
                    Text(
                        "Todavía no guardaste ninguna API. Elegí un proveedor de abajo (Gemini, OpenAI, Groq…): sólo necesitás pegar la clave.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                vm.apiPresets.forEach { preset ->
                ApiCard(
                    vm,
                    preset,
                    onEdit = {
                        editingId = preset.id
                        label = preset.label
                        baseUrl = preset.baseUrl
                        apiKey = preset.apiKey
                        model = preset.model
                        provider = API_PROVIDERS.firstOrNull { it.baseUrl == preset.baseUrl }?.name
                            ?: CUSTOM_PROVIDER
                        scope.launch { scroll.animateScrollTo(0) }
                    },
                    onDocs = {
                        docsMotorId = preset.id
                        docsMotorTitle = preset.label
                    },
                )
            }

                // Alta de API
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (editingId != null) "Editar API" else "Agregar API",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Plantillas con los datos fijos de cada proveedor ya puestos: " +
                                "elegís la plantilla y sólo completás nombre, modelo y clave. " +
                                "Con \"Otro (a mano)\" cargás URL y modelo libremente.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            (API_PROVIDERS.filter { it.enabled }.map { it.name } + CUSTOM_PROVIDER)
                                .forEach { name ->
                                FilterChip(
                                    selected = provider == name,
                                    onClick = {
                                        provider = name
                                        val p = API_PROVIDERS.firstOrNull { it.name == name }
                                        if (p != null) {
                                            baseUrl = p.baseUrl
                                            if (p.model.isNotBlank()) model = p.model
                                            if (label.isBlank() || label == seed?.label ||
                                                API_PROVIDERS.any { it.name == label }
                                            ) label = p.name
                                        }
                                    },
                                    label = { Text(name, fontSize = 12.sp) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            label = { Text("Nombre (ej. Gemini personal, Mi servidor)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (urlLocked) {
                            // URL automática del proveedor: no se pide, se muestra.
                            Text(
                                "URL base (automática): $autoUrl",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("URL base (ej. https://mi-servidor/v1)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = {
                                Text(
                                    if (provider == "Google Gemini") "Modelo (ej. gemini-3.8-flash, gemini-3.8-pro)"
                                    else "Modelo (ej. gpt-4o-mini, llama-3.3-70b-versatile)"
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        val needsKey = API_PROVIDERS.firstOrNull { it.name == provider }?.needsKey ?: true
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = {
                                Text(if (needsKey) "API key" else "API key (opcional: los locales no la usan)")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    vm.saveApi(editingId, label, baseUrl, apiKey, model, activate = false)
                                    clearForm()
                                },
                                enabled = label.isNotBlank() && baseUrl.isNotBlank() && !vm.busy,
                            ) { Text(if (editingId != null) "Guardar cambios" else "Guardar") }
                            Button(
                                onClick = {
                                    val ok = vm.saveApi(editingId, label, baseUrl, apiKey, model, activate = true)
                                    if (ok) clearForm()
                                },
                                enabled = label.isNotBlank() && baseUrl.isNotBlank() && !vm.busy,
                            ) { Text("Guardar y activar") }
                            OutlinedButton(
                                onClick = { vm.testApi(baseUrl, apiKey, model) },
                                enabled = baseUrl.isNotBlank() && !vm.busy && !vm.testing,
                            ) { Text(if (vm.testing) "Probando…" else "Probar conexión") }
                            if (editingId != null) {
                                OutlinedButton(onClick = ::clearForm, enabled = !vm.busy) {
                                    Text("Cancelar edición")
                                }
                            }
                        }
                        if (vm.testResult.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                vm.testResult,
                                fontSize = 12.sp,
                                color = if (vm.testResult == vm.error) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            } else {
                // Configuración local: el modelo y su descarga viven en Modelos.
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Modelo local (llama.cpp)", style = MaterialTheme.typography.titleMedium)
                        Text("Modelo activo: ${vm.activeModelLabel}", fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Se descarga con progreso desde la pestaña Modelos. Si falta el binario " +
                                "llama-server, la app lo clona y compila sola la primera vez.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.go(Screen.MODELS) }, enabled = !vm.busy) {
                                Text("Ir a Modelos")
                            }
                            OutlinedButton(
                                onClick = {
                                    docsMotorId = "local"
                                    docsMotorTitle = vm.activeModelLabel
                                },
                                enabled = !vm.busy,
                            ) { Text("Documentación") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // ---------- Panel "Documentación" (por motor) ----------
        docsMotorId?.let { motorId ->
            DocsPanel(
                vm = vm,
                motorId = motorId,
                title = docsMotorTitle,
                onClose = { docsMotorId = null },
            )
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/** Un segmento del control LOCAL/REMOTO (seleccionado = contorno y relleno de acento). */
@Composable
private fun ModeSegment(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ApiCard(vm: ChatViewModel, preset: ApiPreset, onEdit: () -> Unit, onDocs: () -> Unit) {
    val isActive = vm.activeApiId == preset.id && vm.configMode == "REMOTE"
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !vm.busy && !isActive) { vm.activateApi(preset) }
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(12.dp),
            ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preset.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (isActive) Text("● Activa", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            Text(preset.baseUrl, fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (preset.model.isNotBlank()) Text("Modelo: ${preset.model}", fontSize = 12.sp)
            Text(
                if (preset.apiKey.isBlank()) "Sin clave (localhost)" else "Clave guardada ✔",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isActive) {
                    Button(onClick = { vm.activateApi(preset) }, enabled = !vm.busy) { Text("Activar") }
                }
                OutlinedButton(onClick = onEdit, enabled = !vm.busy) { Text("Editar") }
                OutlinedButton(onClick = { vm.removeApi(preset.id) }, enabled = !vm.busy) { Text("Quitar") }
                OutlinedButton(onClick = onDocs, enabled = !vm.busy) { Text("Documentación") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Panel "Documentación": archivos cargados por UN motor (API o modelo local).
// Lista nombre + extensión, permite agregar desde el SO y eliminar.
// ---------------------------------------------------------------------------

/** Abre el selector de archivos del SO (hilo de Swing) y devuelve las rutas. */
private fun pickDocsFiles(): List<Path> {
    var chosen: List<Path> = emptyList()
    val open = {
        val chooser = JFileChooser()
        chooser.fileSelectionMode = JFileChooser.FILES_ONLY
        chooser.isMultiSelectionEnabled = true
        chooser.dialogTitle = "Elegir documentación"
        // Sólo formatos que el indexer de texto puede leer.
        chooser.fileFilter = FileNameExtensionFilter(
            "Documentos de texto (*.md, *.txt, *.rst, *.adoc, *.mdx)",
            "md", "txt", "rst", "adoc", "mdx",
        )
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chosen = chooser.selectedFiles.map { it.toPath() }
        }
        Unit
    }
    // El selector de archivos DEBE abrirse en el hilo de Swing.
    if (SwingUtilities.isEventDispatchThread()) open() else SwingUtilities.invokeAndWait(open)
    return chosen
}

@Composable
private fun DocsPanel(vm: ChatViewModel, motorId: String, title: String, onClose: () -> Unit) {
    // Recarga la lista cada vez que cambia motor o tras agregar/quitar archivos.
    var files by remember(motorId) { mutableStateOf(vm.listDocs(motorId)) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Documentación — $title") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "Archivos indexados para este motor. Formatos de texto: " +
                        "md, txt, rst, adoc, mdx.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (files.isEmpty()) {
                    Text(
                        "Sin archivos cargados todavía.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                files.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            // Nombre completo + extensión y peso, bien visibles.
                            Text(f.name, fontSize = 12.sp, maxLines = 2)
                            Text(
                                "Extensión: .${f.ext} · ${humanSize(f.size)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = {
                                vm.removeDocsFile(motorId, f.name)
                                files = vm.listDocs(motorId)
                            },
                            enabled = !vm.busy,
                        ) { Text("Eliminar") }
                    }
                    HorizontalDivider()
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val picked = pickDocsFiles()
                        if (picked.isNotEmpty()) {
                            vm.addDocsFiles(motorId, picked)
                            files = vm.listDocs(motorId)
                        }
                    },
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Agregar archivos desde el sistema…") }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onClose) { Text("Cerrar") }
        },
    )
}
