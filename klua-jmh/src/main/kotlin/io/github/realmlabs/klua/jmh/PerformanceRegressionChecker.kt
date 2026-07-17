package io.github.realmlabs.klua.jmh

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt
import kotlin.system.exitProcess

internal data class AcceptedPerformance(
    val timeScoreUs: Double,
    val timeErrorUs: Double,
    val allocationBytes: Double,
)

internal data class TimingMeasurement(
    val scoreUs: Double,
    val errorUs: Double,
)

internal data class PerformanceComparison(
    val benchmark: String,
    val timingSlowdownPercent: Double,
    val timingDeltaUs: Double,
    val combinedTimingErrorUs: Double,
    val allocationIncreasePercent: Double,
) {
    val isTimingCandidate: Boolean
        get() = timingSlowdownPercent > TIMING_GATE_PERCENT && timingDeltaUs > combinedTimingErrorUs

    val isAllocationRegression: Boolean
        get() = allocationIncreasePercent > ALLOCATION_GATE_PERCENT
}

internal data class PerformanceAudit(
    val comparisons: List<PerformanceComparison>,
) {
    val passes: Boolean
        get() = comparisons.none { comparison ->
            comparison.isTimingCandidate || comparison.isAllocationRegression
        }

    fun render(): String {
        val issues = comparisons.flatMap { comparison ->
            buildList {
                if (comparison.isTimingCandidate) {
                    add(
                        "TIMING_CANDIDATE ${comparison.benchmark} " +
                            "slowdown=${comparison.timingSlowdownPercent.formatPercent()} " +
                            "delta=${comparison.timingDeltaUs.formatNumber()}us " +
                            "combinedError=${comparison.combinedTimingErrorUs.formatNumber()}us",
                    )
                }
                if (comparison.isAllocationRegression) {
                    add(
                        "ALLOCATION_REGRESSION ${comparison.benchmark} " +
                            "increase=${comparison.allocationIncreasePercent.formatPercent()}",
                    )
                }
            }
        }
        return if (issues.isEmpty()) {
            "PASS ${comparisons.size} benchmarks; no timing confirmation candidates or allocation regressions"
        } else {
            issues.joinToString(separator = System.lineSeparator()) +
                System.lineSeparator() +
                "FAIL ${issues.size} gate issue(s) across ${comparisons.size} benchmarks"
        }
    }
}

internal object PerformanceRegressionPolicy {
    fun audit(
        accepted: Map<String, AcceptedPerformance>,
        timing: Map<String, TimingMeasurement>,
        allocation: Map<String, Double>,
    ): PerformanceAudit {
        requireCompleteSet("timing", accepted.keys, timing.keys)
        requireCompleteSet("allocation", accepted.keys, allocation.keys)
        val comparisons = accepted.keys.sorted().map { benchmark ->
            val baseline = accepted.getValue(benchmark)
            val currentTiming = timing.getValue(benchmark)
            val currentAllocation = allocation.getValue(benchmark)
            PerformanceComparison(
                benchmark = benchmark,
                timingSlowdownPercent = percentChange(baseline.timeScoreUs, currentTiming.scoreUs),
                timingDeltaUs = currentTiming.scoreUs - baseline.timeScoreUs,
                combinedTimingErrorUs = sqrt(
                    baseline.timeErrorUs * baseline.timeErrorUs +
                        currentTiming.errorUs * currentTiming.errorUs,
                ),
                allocationIncreasePercent = percentChange(baseline.allocationBytes, currentAllocation),
            )
        }
        return PerformanceAudit(comparisons)
    }

    private fun requireCompleteSet(label: String, expected: Set<String>, actual: Set<String>) {
        val missing = (expected - actual).sorted()
        val unexpected = (actual - expected).sorted()
        require(missing.isEmpty() && unexpected.isEmpty()) {
            "$label benchmark set mismatch; missing=$missing unexpected=$unexpected"
        }
    }

    private fun percentChange(baseline: Double, current: Double): Double {
        require(baseline > 0.0 && baseline.isFinite()) { "baseline must be positive and finite: $baseline" }
        require(current >= 0.0 && current.isFinite()) { "measurement must be non-negative and finite: $current" }
        return (current - baseline) / baseline * 100.0
    }
}

internal object PerformanceRegressionChecker {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 3) {
            System.err.println("usage: PerformanceRegressionChecker <baseline.csv> <timing.csv> <gc.csv>")
            exitProcess(2)
        }
        val audit = try {
            PerformanceRegressionPolicy.audit(
                accepted = PerformanceCsv.accepted(Files.readString(Path.of(args[0]))),
                timing = PerformanceCsv.timing(Files.readString(Path.of(args[1]))),
                allocation = PerformanceCsv.allocation(Files.readString(Path.of(args[2]))),
            )
        } catch (error: Exception) {
            System.err.println("performance audit error: ${error.message ?: error::class.java.simpleName}")
            exitProcess(2)
        }
        println(audit.render())
        if (!audit.passes) exitProcess(1)
    }
}

private const val TIMING_GATE_PERCENT = 10.0
private const val ALLOCATION_GATE_PERCENT = 5.0

private fun Double.formatPercent(): String = "%.2f%%".format(java.util.Locale.ROOT, this)

private fun Double.formatNumber(): String = "%.3f".format(java.util.Locale.ROOT, this)
