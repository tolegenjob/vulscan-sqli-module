package io.vulscan.sqli.plan

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ValueTypeInfererTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "42|NUMERIC",
            "4.2|NUMERIC",
            "0|NUMERIC",
            "-7|NUMERIC",
            "0.5|NUMERIC",
            "042|STRING",
            "abc|STRING",
            "1e5|STRING",
            "4.|STRING",
            ".5|STRING",
            "1,5|STRING",
            "42abc|STRING",
            "+5|STRING",
        ],
    )
    fun `value type is inferred from the sample`(sample: String, expected: ValueType) {
        assertThat(ValueTypeInferer.infer(sample)).isEqualTo(expected)
    }

    @org.junit.jupiter.api.Test
    fun `empty or missing sample is a string`() {
        assertThat(ValueTypeInferer.infer("")).isEqualTo(ValueType.STRING)
        assertThat(ValueTypeInferer.infer(null)).isEqualTo(ValueType.STRING)
    }
}
