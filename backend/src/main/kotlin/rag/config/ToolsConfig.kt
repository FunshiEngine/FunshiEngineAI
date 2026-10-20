package rag.config

import kotlinx.serialization.Serializable

/**
 * Configuración de las herramientas (sección `tools` de la config v4).
 *
 * Antes estos valores vivían como constantes en el código; ahora son
 * configurables, con los mismos valores por defecto (una config vieja migrada
 * se comporta exactamente igual que antes).
 */
@Serializable
data class ToolsConfig(
    /**
     * Interruptor general de las herramientas. Apagado, el modelo no recibe
     * ninguna tool en el catálogo (ni las de prueba).
     */
    val enabled: Boolean = true,
    /**
     * Timeout por herramienta, en milisegundos. 0 = sin límite.
     * A los 60 s una tool colgada se corta y el modelo recibe un error
     * controlado en vez de quedarse esperando.
     */
    val timeoutMs: Long = 60_000,
)

/**
 * Configuración de las salidas (sección `outputs` de la config v4).
 *
 * [dir] es relativa a la carpeta de trabajo de la app (`app-home`): los
 * archivos de cada chat van a `<app-home>/<dir>/<chat-id>/`.
 */
@Serializable
data class OutputsConfig(
    /** Carpeta (relativa a app-home) donde se dejan los archivos de los chats. */
    val dir: String = "outputs",
    /** Tope por archivo creado con `create_file` (MB). */
    val maxFileMb: Int = 5,
    /** Tope por archivo descargado con `download_url` (MB). */
    val maxDownloadMb: Int = 50,
) {
    /** Tope por archivo creado, en bytes. */
    val maxFileBytes: Long get() = maxFileMb.coerceAtLeast(1) * 1024L * 1024L

    /** Tope por descarga, en bytes. */
    val maxDownloadBytes: Long get() = maxDownloadMb.coerceAtLeast(1) * 1024L * 1024L
}

/**
 * Configuración de LaTeX (sección `latex` de la config v4).
 */
@Serializable
data class LatexConfig(
    /**
     * Motor de compilación. Vacío = autodetectar (`pdflatex` y, si no está,
     * `xelatex`). Forcedo = sólo ese motor (y si no está, la tool falla
     * rápido diciendo cuál falta).
     */
    val engine: String = "",
    /** Timeout de compilación (ms). */
    val timeoutMs: Long = 60_000,
    /** Tope del documento (KB). */
    val maxDocumentKb: Int = 1024,
) {
    /** Tope del documento, en bytes. */
    val maxDocumentBytes: Long get() = maxDocumentKb.coerceAtLeast(1) * 1024L
}
