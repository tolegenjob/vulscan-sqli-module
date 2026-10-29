package io.vulscan.sqli.plan

enum class ValueType { NUMERIC, STRING }

/** Тип значения определяет, нужны ли кавычки в пробах: числовой контекст в SQL обычно не заключается в кавычки. */
object ValueTypeInferer {
    private val NUMBER = Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?$")

    fun infer(sampleValue: String?): ValueType =
        if (sampleValue != null && NUMBER.matches(sampleValue)) ValueType.NUMERIC else ValueType.STRING
}
