package rag.test

import kotlinx.coroutines.runBlocking

/**
 * Corre un bloque `suspend` dentro de un test.
 *
 * Los tests del backend son todos deterministas (no dependen de la red ni del
 * reloj): `runBlocking` alcanza y sobra, y deja el test como una función
 * normal que se puede leer de un tirón.
 */
fun runTest(block: suspend () -> Unit) = runBlocking { block() }
