package voice.test

import kotlinx.coroutines.runBlocking

/**
 * Corre un bloque `suspend` dentro de un test.
 *
 * Mismo helper que el del backend (`rag.test.runTest`): los tests de voz son
 * deterministas (nada de red, nada de reloj), así que `runBlocking` alcanza.
 */
fun runTest(block: suspend () -> Unit) = runBlocking { block() }
