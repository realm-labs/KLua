package io.github.realmlabs.klua.examples

import kotlin.test.Test
import kotlin.test.assertEquals

class EmbeddingExamplesTest {
    @Test
    fun `Kotlin example returns expected value`() {
        assertEquals(42L, KotlinEmbeddingExample.evaluate())
    }

    @Test
    fun `Java example returns expected value`() {
        assertEquals(42L, JavaEmbeddingExample.evaluate())
    }
}
