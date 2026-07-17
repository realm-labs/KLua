package io.github.realmlabs.klua.jmh

internal object PerformanceCsv {
    private const val ALLOCATION_SUFFIX = ":·gc.alloc.rate.norm"

    fun accepted(text: String): Map<String, AcceptedPerformance> {
        return uniqueRows(parse(text), "accepted baseline") { row ->
            row.required("Benchmark") to AcceptedPerformance(
                timeScoreUs = row.requiredDouble("TimeScoreUs"),
                timeErrorUs = row.requiredDouble("TimeErrorUs"),
                allocationBytes = row.requiredDouble("AllocationBytes"),
            )
        }.also { accepted ->
            accepted.forEach { (benchmark, value) ->
                require(value.timeScoreUs > 0.0) { "non-positive accepted time for $benchmark" }
                require(value.timeErrorUs >= 0.0) { "negative accepted time error for $benchmark" }
                require(value.allocationBytes > 0.0) { "non-positive accepted allocation for $benchmark" }
            }
        }
    }

    fun timing(text: String): Map<String, TimingMeasurement> {
        val rows = parse(text).filter { row ->
            row.required("Unit") == "us/op" && ALLOCATION_SUFFIX !in row.required("Benchmark")
        }
        return uniqueRows(rows, "timing CSV") { row ->
            require(row.required("Mode") == "avgt") { "timing benchmark is not average-time mode" }
            row.required("Benchmark") to TimingMeasurement(
                scoreUs = row.requiredDouble("Score"),
                errorUs = row.requiredDouble("Score Error (99.9%)"),
            )
        }
    }

    fun allocation(text: String): Map<String, Double> {
        val rows = parse(text).filter { row -> row.required("Benchmark").endsWith(ALLOCATION_SUFFIX) }
        return uniqueRows(rows, "GC CSV") { row ->
            require(row.required("Unit") == "B/op") { "normalized allocation benchmark is not B/op" }
            row.required("Benchmark").removeSuffix(ALLOCATION_SUFFIX) to row.requiredDouble("Score")
        }
    }

    private fun parse(text: String): List<Map<String, String>> {
        val records = parseRecords(text)
        require(records.isNotEmpty()) { "CSV is empty" }
        val header = records.first()
        require(header.isNotEmpty() && header.distinct().size == header.size) { "CSV header is empty or duplicated" }
        return records.drop(1).filterNot { record -> record.size == 1 && record.single().isBlank() }.mapIndexed { index, record ->
            require(record.size == header.size) {
                "CSV row ${index + 2} has ${record.size} columns; expected ${header.size}"
            }
            header.zip(record).toMap()
        }
    }

    private fun parseRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                quoted && character == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                !quoted && character == ',' -> {
                    record += field.toString()
                    field.setLength(0)
                }
                !quoted && (character == '\n' || character == '\r') -> {
                    record += field.toString()
                    field.setLength(0)
                    records += record
                    record = mutableListOf()
                    if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                }
                else -> field.append(character)
            }
            index++
        }
        require(!quoted) { "unterminated quoted CSV field" }
        if (field.isNotEmpty() || record.isNotEmpty()) {
            record += field.toString()
            records += record
        }
        return records
    }

    private fun <T> uniqueRows(
        rows: List<Map<String, String>>,
        label: String,
        transform: (Map<String, String>) -> Pair<String, T>,
    ): Map<String, T> {
        val result = linkedMapOf<String, T>()
        rows.forEach { row ->
            val (benchmark, value) = transform(row)
            require(result.put(benchmark, value) == null) { "duplicate benchmark in $label: $benchmark" }
        }
        return result
    }

    private fun Map<String, String>.required(column: String): String {
        return this[column]?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("CSV is missing required value for $column")
    }

    private fun Map<String, String>.requiredDouble(column: String): Double {
        val value = required(column).toDoubleOrNull()
            ?: throw IllegalArgumentException("CSV value for $column is not numeric: ${this[column]}")
        require(value.isFinite()) { "CSV value for $column is not finite: $value" }
        return value
    }
}
