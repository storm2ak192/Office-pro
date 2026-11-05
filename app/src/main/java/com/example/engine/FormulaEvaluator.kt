package com.example.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

object FormulaEvaluator {

    fun parseCellRef(ref: String): Pair<Int, Int>? {
        val clean = ref.trim().replace("$", "").uppercase(Locale.US)
        val match = Regex("^([A-Z]+)(\\d+)$").find(clean) ?: return null
        val colStr = match.groupValues[1]
        val rowNum = match.groupValues[2].toIntOrNull() ?: return null
        if (rowNum <= 0) return null
        var col = 0
        for (ch in colStr) {
            col = col * 26 + (ch - 'A' + 1)
        }
        return (rowNum - 1) to (col - 1)
    }

    fun colIndexToName(col: Int): String {
        var c = col + 1
        val sb = StringBuilder()
        while (c > 0) {
            val rem = (c - 1) % 26
            sb.append(('A'.code + rem).toChar())
            c = (c - 1) / 26
        }
        return sb.reverse().toString()
    }

    fun cellKey(row: Int, col: Int): String = "$row,$col"

    fun recalculateSheet(sheet: ExcelSheet): ExcelSheet {
        val cache = mutableMapOf<String, Any?>()
        val updatedCells = sheet.cells.toMutableMap()
        for ((key, cell) in sheet.cells) {
            val parts = key.split(",")
            val r = parts[0].toIntOrNull() ?: continue
            val c = parts[1].toIntOrNull() ?: continue
            val evaluated = evaluateCell(r, c, sheet, cache, mutableSetOf())
            val formatted = formatValue(evaluated, cell.numberFormat)
            updatedCells[key] = cell.copy(displayValue = formatted)
        }
        return sheet.copy(cells = updatedCells)
    }

    fun formatValue(value: Any?, format: CellNumberFormat): String {
        if (value == null) return ""
        if (value is String && value.startsWith("#")) return value
        val num = toDoubleOrNull(value)
        return when (format) {
            CellNumberFormat.GENERAL -> {
                if (num != null) {
                    if (num == num.toLong().toDouble()) num.toLong().toString()
                    else String.format(Locale.US, "%.2f", num)
                } else value.toString()
            }
            CellNumberFormat.CURRENCY -> {
                if (num != null) String.format(Locale.US, "$%,.2f", num) else value.toString()
            }
            CellNumberFormat.PERCENT -> {
                if (num != null) String.format(Locale.US, "%.1f%%", num * 100.0) else value.toString()
            }
            CellNumberFormat.DATE -> {
                if (num != null && num > 1000000) {
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(num.toLong()))
                } else value.toString()
            }
        }
    }

    private fun evaluateCell(
        row: Int,
        col: Int,
        sheet: ExcelSheet,
        cache: MutableMap<String, Any?>,
        visiting: MutableSet<String>
    ): Any? {
        val key = cellKey(row, col)
        if (cache.containsKey(key)) return cache[key]
        if (!visiting.add(key)) return "#REF!"

        val raw = sheet.cells[key]?.rawInput ?: ""
        val result: Any? = if (raw.startsWith("=")) {
            try {
                Parser(raw.substring(1), sheet, cache, visiting).parseExpression()
            } catch (e: ArithmeticException) {
                "#DIV/0!"
            } catch (e: Exception) {
                "#VALUE!"
            }
        } else {
            raw.toDoubleOrNull() ?: raw
        }

        visiting.remove(key)
        cache[key] = result
        return result
    }

    private fun toDoubleOrNull(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    private fun toBoolean(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.equals("TRUE", ignoreCase = true) || (v.toDoubleOrNull()?.let { it != 0.0 } ?: false)
        else -> false
    }

    private class Parser(
        private val input: String,
        private val sheet: ExcelSheet,
        private val cache: MutableMap<String, Any?>,
        private val visiting: MutableSet<String>
    ) {
        private var pos = 0

        private fun skipWs() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        fun parseExpression(): Any? = parseComparison()

        private fun parseComparison(): Any? {
            var left = parseConcat()
            while (true) {
                skipWs()
                val op = when {
                    input.startsWith("<>", pos) -> { pos += 2; "<>" }
                    input.startsWith("<=", pos) -> { pos += 2; "<=" }
                    input.startsWith(">=", pos) -> { pos += 2; ">=" }
                    pos < input.length && input[pos] == '=' -> { pos++; "=" }
                    pos < input.length && input[pos] == '<' -> { pos++; "<" }
                    pos < input.length && input[pos] == '>' -> { pos++; ">" }
                    else -> break
                }
                val right = parseConcat()
                left = compareValues(left, right, op)
            }
            return left
        }

        private fun compareValues(l: Any?, r: Any?, op: String): Boolean {
            val ln = toDoubleOrNull(l)
            val rn = toDoubleOrNull(r)
            val cmp = if (ln != null && rn != null) {
                ln.compareTo(rn)
            } else {
                (l?.toString() ?: "").compareTo(r?.toString() ?: "", ignoreCase = true)
            }
            return when (op) {
                "=" -> cmp == 0
                "<>" -> cmp != 0
                "<" -> cmp < 0
                ">" -> cmp > 0
                "<=" -> cmp <= 0
                ">=" -> cmp >= 0
                else -> false
            }
        }

        private fun parseConcat(): Any? {
            var left = parseAddSub()
            while (true) {
                skipWs()
                if (pos < input.length && input[pos] == '&') {
                    pos++
                    val right = parseAddSub()
                    left = (left?.toString() ?: "") + (right?.toString() ?: "")
                } else break
            }
            return left
        }

        private fun parseAddSub(): Any? {
            var left = parseMulDiv()
            while (true) {
                skipWs()
                if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) {
                    val op = input[pos++]
                    val right = parseMulDiv()
                    val ln = toDoubleOrNull(left) ?: 0.0
                    val rn = toDoubleOrNull(right) ?: 0.0
                    left = if (op == '+') ln + rn else ln - rn
                } else break
            }
            return left
        }

        private fun parseMulDiv(): Any? {
            var left = parsePower()
            while (true) {
                skipWs()
                if (pos < input.length && (input[pos] == '*' || input[pos] == '/')) {
                    val op = input[pos++]
                    val right = parsePower()
                    val ln = toDoubleOrNull(left) ?: 0.0
                    val rn = toDoubleOrNull(right) ?: 0.0
                    if (op == '/' && rn == 0.0) throw ArithmeticException("Division by zero")
                    left = if (op == '*') ln * rn else ln / rn
                } else break
            }
            return left
        }

        private fun parsePower(): Any? {
            var left = parseUnary()
            skipWs()
            if (pos < input.length && input[pos] == '^') {
                pos++
                val right = parsePower()
                val ln = toDoubleOrNull(left) ?: 0.0
                val rn = toDoubleOrNull(right) ?: 0.0
                left = ln.pow(rn)
            }
            return left
        }

        private fun parseUnary(): Any? {
            skipWs()
            if (pos < input.length && input[pos] == '-') {
                pos++
                return -(toDoubleOrNull(parseUnary()) ?: 0.0)
            }
            if (pos < input.length && input[pos] == '+') {
                pos++
                return toDoubleOrNull(parseUnary()) ?: 0.0
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Any? {
            skipWs()
            if (pos >= input.length) return ""

            // Parentheses
            if (input[pos] == '(') {
                pos++
                val v = parseExpression()
                skipWs()
                if (pos < input.length && input[pos] == ')') pos++
                return v
            }

            // String literal
            if (input[pos] == '"') {
                pos++
                val sb = StringBuilder()
                while (pos < input.length && input[pos] != '"') {
                    sb.append(input[pos++])
                }
                if (pos < input.length && input[pos] == '"') pos++
                return sb.toString()
            }

            // Number
            if (input[pos].isDigit() || input[pos] == '.') {
                val start = pos
                while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
                var num = input.substring(start, pos).toDoubleOrNull() ?: 0.0
                if (pos < input.length && input[pos] == '%') {
                    pos++
                    num /= 100.0
                }
                return num
            }

            // Identifier: Function call or Cell reference
            val start = pos
            while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '$' || input[pos] == '_')) {
                pos++
            }
            val ident = input.substring(start, pos)
            skipWs()
            if (pos < input.length && input[pos] == '(') {
                pos++ // consume '('
                return evaluateFunction(ident.uppercase(Locale.US))
            }

            if (ident.equals("TRUE", true)) return true
            if (ident.equals("FALSE", true)) return false

            val ref = parseCellRef(ident) ?: return "#NAME?"
            return evaluateCell(ref.first, ref.second, sheet, cache, visiting)
        }

        private fun parseArgValues(): List<Any?> {
            val args = mutableListOf<Any?>()
            skipWs()
            if (pos < input.length && input[pos] == ')') {
                pos++
                return args
            }
            while (pos < input.length) {
                skipWs()
                // Check if argument is a range like A1:C5
                val rangeMatch = Regex("^(\\$?[A-Za-z]+\\$?\\d+):(\\$?[A-Za-z]+\\$?\\d+)").find(input.substring(pos))
                if (rangeMatch != null) {
                    val startRef = parseCellRef(rangeMatch.groupValues[1])
                    val endRef = parseCellRef(rangeMatch.groupValues[2])
                    pos += rangeMatch.value.length
                    if (startRef != null && endRef != null) {
                        val r1 = minOf(startRef.first, endRef.first)
                        val r2 = maxOf(startRef.first, endRef.first)
                        val c1 = minOf(startRef.second, endRef.second)
                        val c2 = maxOf(startRef.second, endRef.second)
                        for (r in r1..r2) {
                            for (c in c1..c2) {
                                args.add(evaluateCell(r, c, sheet, cache, visiting))
                            }
                        }
                    }
                } else {
                    args.add(parseExpression())
                }
                skipWs()
                if (pos < input.length && (input[pos] == ',' || input[pos] == ';')) {
                    pos++
                } else if (pos < input.length && input[pos] == ')') {
                    pos++
                    break
                } else {
                    break
                }
            }
            return args
        }

        private fun parseRangeMatrix(): List<List<Any?>>? {
            skipWs()
            val rangeMatch = Regex("^(\\$?[A-Za-z]+\\$?\\d+):(\\$?[A-Za-z]+\\$?\\d+)").find(input.substring(pos))
                ?: return null
            val startRef = parseCellRef(rangeMatch.groupValues[1]) ?: return null
            val endRef = parseCellRef(rangeMatch.groupValues[2]) ?: return null
            pos += rangeMatch.value.length
            val r1 = minOf(startRef.first, endRef.first)
            val r2 = maxOf(startRef.first, endRef.first)
            val c1 = minOf(startRef.second, endRef.second)
            val c2 = maxOf(startRef.second, endRef.second)
            val matrix = mutableListOf<List<Any?>>()
            for (r in r1..r2) {
                val rowList = mutableListOf<Any?>()
                for (c in c1..c2) {
                    rowList.add(evaluateCell(r, c, sheet, cache, visiting))
                }
                matrix.add(rowList)
            }
            return matrix
        }

        private fun consumeCommaOrClose(): Boolean {
            skipWs()
            if (pos < input.length && (input[pos] == ',' || input[pos] == ';')) {
                pos++
                return true
            }
            if (pos < input.length && input[pos] == ')') {
                pos++
            }
            return false
        }

        private fun evaluateFunction(name: String): Any? {
            return when (name) {
                "SUM" -> parseArgValues().mapNotNull { toDoubleOrNull(it) }.sum()
                "AVERAGE", "AVG" -> {
                    val nums = parseArgValues().mapNotNull { toDoubleOrNull(it) }
                    if (nums.isEmpty()) "#DIV/0!" else nums.average()
                }
                "COUNT" -> parseArgValues().count { toDoubleOrNull(it) != null }.toDouble()
                "COUNTA" -> parseArgValues().count { it != null && it.toString().isNotBlank() }.toDouble()
                "MIN" -> parseArgValues().mapNotNull { toDoubleOrNull(it) }.minOrNull() ?: 0.0
                "MAX" -> parseArgValues().mapNotNull { toDoubleOrNull(it) }.maxOrNull() ?: 0.0
                "IF" -> {
                    val cond = parseExpression()
                    val trueVal = if (consumeCommaOrClose()) parseExpression() else true
                    val falseVal = if (consumeCommaOrClose()) parseExpression() else false
                    skipWs(); if (pos < input.length && input[pos] == ')') pos++
                    if (toBoolean(cond)) trueVal else falseVal
                }
                "AND" -> parseArgValues().all { toBoolean(it) }
                "OR" -> parseArgValues().any { toBoolean(it) }
                "NOT" -> {
                    val v = parseArgValues().firstOrNull()
                    !toBoolean(v)
                }
                "COUNTIF" -> {
                    val matrix = parseRangeMatrix()?.flatten() ?: emptyList()
                    consumeCommaOrClose()
                    val criteria = parseExpression()?.toString() ?: ""
                    skipWs(); if (pos < input.length && input[pos] == ')') pos++
                    matrix.count { matchesCriteria(it, criteria) }.toDouble()
                }
                "SUMIF" -> {
                    val range = parseRangeMatrix()?.flatten() ?: emptyList()
                    consumeCommaOrClose()
                    val criteria = parseExpression()?.toString() ?: ""
                    val sumRange = if (consumeCommaOrClose()) {
                        val m = parseRangeMatrix()?.flatten() ?: range
                        skipWs(); if (pos < input.length && input[pos] == ')') pos++
                        m
                    } else range
                    var total = 0.0
                    for (i in range.indices) {
                        if (matchesCriteria(range[i], criteria)) {
                            total += toDoubleOrNull(sumRange.getOrNull(i)) ?: 0.0
                        }
                    }
                    total
                }
                "AVERAGEIF" -> {
                    val range = parseRangeMatrix()?.flatten() ?: emptyList()
                    consumeCommaOrClose()
                    val criteria = parseExpression()?.toString() ?: ""
                    val avgRange = if (consumeCommaOrClose()) {
                        val m = parseRangeMatrix()?.flatten() ?: range
                        skipWs(); if (pos < input.length && input[pos] == ')') pos++
                        m
                    } else range
                    var total = 0.0
                    var count = 0
                    for (i in range.indices) {
                        if (matchesCriteria(range[i], criteria)) {
                            val n = toDoubleOrNull(avgRange.getOrNull(i))
                            if (n != null) {
                                total += n
                                count++
                            }
                        }
                    }
                    if (count == 0) "#DIV/0!" else total / count
                }
                "VLOOKUP" -> {
                    val lookup = parseExpression()
                    consumeCommaOrClose()
                    val table = parseRangeMatrix() ?: return "#REF!"
                    consumeCommaOrClose()
                    val colIdx = (toDoubleOrNull(parseExpression())?.toInt() ?: 1) - 1
                    if (consumeCommaOrClose()) parseExpression() // optional exactMatch
                    skipWs(); if (pos < input.length && input[pos] == ')') pos++
                    if (colIdx < 0 || (table.isNotEmpty() && colIdx >= table[0].size)) return "#REF!"
                    val foundRow = table.firstOrNull { row ->
                        compareValues(row.firstOrNull(), lookup, "=")
                    }
                    foundRow?.getOrNull(colIdx) ?: "#N/A"
                }
                "HLOOKUP" -> {
                    val lookup = parseExpression()
                    consumeCommaOrClose()
                    val table = parseRangeMatrix() ?: return "#REF!"
                    consumeCommaOrClose()
                    val rowIdx = (toDoubleOrNull(parseExpression())?.toInt() ?: 1) - 1
                    if (consumeCommaOrClose()) parseExpression()
                    skipWs(); if (pos < input.length && input[pos] == ')') pos++
                    if (rowIdx < 0 || rowIdx >= table.size || table.isEmpty()) return "#REF!"
                    val colIdx = table[0].indexOfFirst { compareValues(it, lookup, "=") }
                    if (colIdx == -1) "#N/A" else table[rowIdx].getOrNull(colIdx) ?: "#N/A"
                }
                "INDEX" -> {
                    val table = parseRangeMatrix() ?: return "#REF!"
                    consumeCommaOrClose()
                    val rIdx = (toDoubleOrNull(parseExpression())?.toInt() ?: 1) - 1
                    val cIdx = if (consumeCommaOrClose()) {
                        val c = (toDoubleOrNull(parseExpression())?.toInt() ?: 1) - 1
                        skipWs(); if (pos < input.length && input[pos] == ')') pos++
                        c
                    } else 0
                    table.getOrNull(rIdx)?.getOrNull(cIdx) ?: "#REF!"
                }
                "MATCH" -> {
                    val lookup = parseExpression()
                    consumeCommaOrClose()
                    val list = parseRangeMatrix()?.flatten() ?: return "#REF!"
                    if (consumeCommaOrClose()) parseExpression()
                    skipWs(); if (pos < input.length && input[pos] == ')') pos++
                    val idx = list.indexOfFirst { compareValues(it, lookup, "=") }
                    if (idx == -1) "#N/A" else (idx + 1).toDouble()
                }
                "ROUND" -> {
                    val args = parseArgValues()
                    val n = toDoubleOrNull(args.getOrNull(0)) ?: 0.0
                    val d = toDoubleOrNull(args.getOrNull(1))?.toInt() ?: 0
                    val factor = 10.0.pow(d)
                    round(n * factor) / factor
                }
                "ABS" -> abs(toDoubleOrNull(parseArgValues().firstOrNull()) ?: 0.0)
                "SQRT" -> {
                    val n = toDoubleOrNull(parseArgValues().firstOrNull()) ?: 0.0
                    if (n < 0) "#NUM!" else sqrt(n)
                }
                "POWER" -> {
                    val args = parseArgValues()
                    val b = toDoubleOrNull(args.getOrNull(0)) ?: 0.0
                    val e = toDoubleOrNull(args.getOrNull(1)) ?: 1.0
                    b.pow(e)
                }
                "CONCATENATE", "CONCAT" -> parseArgValues().joinToString("") { it?.toString() ?: "" }
                "LEN" -> (parseArgValues().firstOrNull()?.toString()?.length ?: 0).toDouble()
                "UPPER" -> parseArgValues().firstOrNull()?.toString()?.uppercase(Locale.getDefault()) ?: ""
                "LOWER" -> parseArgValues().firstOrNull()?.toString()?.lowercase(Locale.getDefault()) ?: ""
                "TRIM" -> parseArgValues().firstOrNull()?.toString()?.trim() ?: ""
                "TODAY" -> {
                    parseArgValues()
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                }
                "NOW" -> {
                    parseArgValues()
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                }
                else -> {
                    parseArgValues()
                    "#NAME?"
                }
            }
        }

        private fun matchesCriteria(value: Any?, criteria: String): Boolean {
            val c = criteria.trim()
            return when {
                c.startsWith(">=") -> compareValues(value, c.substring(2).toDoubleOrNull() ?: c.substring(2), ">=")
                c.startsWith("<=") -> compareValues(value, c.substring(2).toDoubleOrNull() ?: c.substring(2), "<=")
                c.startsWith("<>") -> compareValues(value, c.substring(2).toDoubleOrNull() ?: c.substring(2), "<>")
                c.startsWith(">") -> compareValues(value, c.substring(1).toDoubleOrNull() ?: c.substring(1), ">")
                c.startsWith("<") -> compareValues(value, c.substring(1).toDoubleOrNull() ?: c.substring(1), "<")
                c.startsWith("=") -> compareValues(value, c.substring(1).toDoubleOrNull() ?: c.substring(1), "=")
                else -> compareValues(value, c.toDoubleOrNull() ?: c, "=")
            }
        }
    }
}
