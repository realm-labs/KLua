package io.github.realmlabs.klua.jmh

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerformanceRegressionCheckerTest {
    @Test
    fun `accepted baseline contains the complete canonical workload set`() {
        val accepted = PerformanceCsv.accepted(Files.readString(Path.of("baselines/v1-jdk17.csv")))

        assertEquals(22, accepted.size)
        assertTrue("io.github.realmlabs.klua.jmh.CompileBenchmark.compileNumericLoop" in accepted)
        assertTrue("io.github.realmlabs.klua.jmh.VmExecutionBenchmark.executeNumericLoop" in accepted)
    }

    @Test
    fun `timing candidate must exceed threshold and combined uncertainty`() {
        val accepted = mapOf("bench" to AcceptedPerformance(100.0, 20.0, 1000.0))

        val threshold = PerformanceRegressionPolicy.audit(
            accepted,
            timing = mapOf("bench" to TimingMeasurement(110.0, 1.0)),
            allocation = mapOf("bench" to 1000.0),
        )
        val uncertain = PerformanceRegressionPolicy.audit(
            accepted,
            timing = mapOf("bench" to TimingMeasurement(115.0, 20.0)),
            allocation = mapOf("bench" to 1000.0),
        )
        val supported = PerformanceRegressionPolicy.audit(
            accepted,
            timing = mapOf("bench" to TimingMeasurement(140.0, 5.0)),
            allocation = mapOf("bench" to 1000.0),
        )

        assertTrue(threshold.passes)
        assertFalse(threshold.comparisons.single().isTimingCandidate)
        assertTrue(uncertain.passes)
        assertFalse(uncertain.comparisons.single().isTimingCandidate)
        assertFalse(supported.passes)
        assertTrue(supported.comparisons.single().isTimingCandidate)
        assertTrue(supported.render().startsWith("TIMING_CANDIDATE bench"))
    }

    @Test
    fun `allocation increase above five percent fails while boundary passes`() {
        val accepted = mapOf("bench" to AcceptedPerformance(100.0, 1.0, 1000.0))

        val boundary = PerformanceRegressionPolicy.audit(
            accepted,
            timing = mapOf("bench" to TimingMeasurement(100.0, 1.0)),
            allocation = mapOf("bench" to 1050.0),
        )
        val regression = PerformanceRegressionPolicy.audit(
            accepted,
            timing = mapOf("bench" to TimingMeasurement(100.0, 1.0)),
            allocation = mapOf("bench" to 1050.1),
        )

        assertTrue(boundary.passes)
        assertFalse(regression.passes)
        assertTrue(regression.comparisons.single().isAllocationRegression)
    }

    @Test
    fun `audit rejects missing and unexpected benchmark rows`() {
        val accepted = mapOf("expected" to AcceptedPerformance(100.0, 1.0, 1000.0))

        val error = assertFailsWith<IllegalArgumentException> {
            PerformanceRegressionPolicy.audit(
                accepted,
                timing = mapOf("unexpected" to TimingMeasurement(100.0, 1.0)),
                allocation = mapOf("expected" to 1000.0),
            )
        }

        assertTrue(error.message.orEmpty().contains("missing=[expected]"))
        assertTrue(error.message.orEmpty().contains("unexpected=[unexpected]"))
    }

    @Test
    fun `JMH parsers read quoted timing and normalized allocation rows`() {
        val timing = PerformanceCsv.timing(
            """
            "Benchmark","Mode","Threads","Samples","Score","Score Error (99.9%)","Unit"
            "bench","avgt",1,5,12.5,0.75,"us/op"
            """.trimIndent(),
        )
        val allocation = PerformanceCsv.allocation(
            """
            "Benchmark","Mode","Threads","Samples","Score","Score Error (99.9%)","Unit"
            "bench","avgt",1,5,12.5,0.75,"us/op"
            "bench:·gc.alloc.rate.norm","avgt",1,5,2048.0,1.0,"B/op"
            """.trimIndent(),
        )

        assertEquals(TimingMeasurement(12.5, 0.75), timing.getValue("bench"))
        assertEquals(2048.0, allocation.getValue("bench"))
    }
}
