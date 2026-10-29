package io.vulscan.sqli.detect

import io.vulscan.sqli.plan.ValueType

/** Пара логически противоположных условий: [trueValue] должно вести себя как baseline, [falseValue] — иначе. */
data class BooleanPair(val trueValue: String, val falseValue: String)

/**
 * Боевые пары (Q-4) по типу значения. Комментарные суффиксы (`-- `, `#`) не добавляются в MVP —
 * нужны только если приложение обрезает хвост строки; это зафиксированное ограничение, а не упущение.
 */
object BooleanProbeSets {
    fun pairs(valueType: ValueType, sample: String): List<BooleanPair> = when (valueType) {
        ValueType.STRING -> listOf(
            BooleanPair("$sample' AND '1'='1", "$sample' AND '1'='2"),
            BooleanPair("$sample' AND 'a'='a", "$sample' AND 'a'='b"),
        )
        ValueType.NUMERIC -> listOf(
            BooleanPair("$sample AND 1=1", "$sample AND 1=2"),
            BooleanPair("$sample AND 2=2", "$sample AND 2=3"),
        )
    }

    /** Синтаксически невалидное, но НЕ логическое значение — отсекает приложения, которые просто нормализуют ввод. */
    fun control(valueType: ValueType, sample: String): String = when (valueType) {
        ValueType.STRING -> "$sample' AND '1'='1'x"
        ValueType.NUMERIC -> "$sample AND 1=1x"
    }
}
