package org.junit

/*
 * Puente para que los tests escritos con JUnit 4 corran tal cual en Kotlin Multiplatform (JVM y JavaScript).
 * Mismos nombres y mismo orden de argumentos que JUnit (el mensaje va PRIMERO), implementados con kotlin.test,
 * donde el mensaje va al final. Así no hubo que reescribir a mano cientos de aserciones con riesgo de invertir
 * esperado y obtenido. Los tests nuevos pueden usar kotlin.test directamente.
 */

typealias Test = kotlin.test.Test

object Assert {
    fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)
    fun assertEquals(message: String, expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual, message)
    fun assertEquals(expected: Double, actual: Double, delta: Double) = kotlin.test.assertEquals(expected, actual, delta)
    fun assertEquals(message: String, expected: Double, actual: Double, delta: Double) =
        kotlin.test.assertEquals(expected, actual, delta, message)

    fun assertTrue(condition: Boolean) = kotlin.test.assertTrue(condition)
    fun assertTrue(message: String, condition: Boolean) = kotlin.test.assertTrue(condition, message)
    fun assertFalse(condition: Boolean) = kotlin.test.assertFalse(condition)
    fun assertFalse(message: String, condition: Boolean) = kotlin.test.assertFalse(condition, message)

    fun assertNull(value: Any?) = kotlin.test.assertNull(value)
    fun assertNull(message: String, value: Any?) = kotlin.test.assertNull(value, message)
    fun assertNotNull(value: Any?) { kotlin.test.assertNotNull(value) }
    fun assertNotNull(message: String, value: Any?) { kotlin.test.assertNotNull(value, message) }
}
