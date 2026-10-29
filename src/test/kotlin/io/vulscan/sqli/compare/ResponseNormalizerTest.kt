package io.vulscan.sqli.compare

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResponseNormalizerTest {
    private fun n(body: String, type: String? = "text/html", value: String? = null) = ResponseNormalizer.normalize(body, type, value)

    @Test
    fun `dates and times are masked`() {
        assertThat(n("<p>Обновлено 2026-10-22T15:04:05Z</p>")).isEqualTo(n("<p>Обновлено 2026-10-23T09:00:00+03:00</p>"))
        assertThat(n("Дата: 2026-01-05")).isEqualTo(n("Дата: 2027-12-31"))
    }

    @Test
    fun `uuids, unix time and long tokens are masked`() {
        assertThat(n("id=0a0f3a2e-6d93-4c8f-b2b6-d1f7e0c4a901")).isEqualTo(n("id=8f0d6d3e-9b52-4f55-8d0e-4f6a2d9e7c01"))
        assertThat(n("ts 1730000000")).isEqualTo(n("ts 1730123456"))
        assertThat(n("ts 1730000000123")).isEqualTo(n("ts 1731111111999"))
        assertThat(n("session 9f86d081884c7d659a2feaa0c55ad015")).isEqualTo(n("session a3c65c2974270fd093ee8bb4aacb1ce1"))
        assertThat(n("token Zm9vYmFyMTIzNDU2Nzg5MA")).isEqualTo(n("token QWJjZGVmZ2hpams5ODc2NTQ"))
    }

    @Test
    fun `csrf and nonce values are masked but ordinary inputs are kept`() {
        val a = "<form><input type=\"hidden\" name=\"csrf_token\" value=\"abc\"><input name=\"q\" value=\"42\"></form>"
        val b = "<form><input type=\"hidden\" name=\"csrf_token\" value=\"xyz\"><input name=\"q\" value=\"42\"></form>"
        assertThat(n(a)).isEqualTo(n(b))
        assertThat(n(a)).contains("value=\"42\"")
        assertThat(n("<meta name=\"csrf-token\" content=\"aaa\">")).isEqualTo(n("<meta name=\"csrf-token\" content=\"bbb\">"))
        assertThat(n("<script nonce=\"r4nd0m\">1</script>")).isEqualTo(n("<script nonce=\"other\">1</script>"))
        assertThat(n("<input name=\"q\" value=\"1\">")).isNotEqualTo(n("<input name=\"q\" value=\"2\">"))
    }

    @Test
    fun `reflected test value is masked in raw, html escaped and url encoded form`() {
        val value = "a'b<c d"
        assertThat(n("<p>Результаты: a'b<c d</p>", value = value)).isEqualTo("<p>Результаты: §</p>")
        assertThat(n("<p>Результаты: a&#39;b&lt;c d</p>", value = value)).isEqualTo("<p>Результаты: §</p>")
        assertThat(n("<a href=\"?q=a%27b%3Cc+d\">x</a>", value = value)).isEqualTo("<a href=\"?q=§\">x</a>")
    }

    @Test
    fun `product text and ordinary numbers survive normalisation`() {
        val text = n("<li>Ноутбук Acer Aspire 5, версия 12, цена 49990</li>")
        assertThat(text).contains("Ноутбук Acer Aspire 5").contains("версия 12").contains("49990")
    }

    @Test
    fun `json is canonicalised with sorted keys and masked values`() {
        val a = """{"b":1,"a":"2026-01-01T00:00:00Z","list":[{"y":2,"x":1}]}"""
        val b = """{ "list" : [ { "x": 1, "y": 2 } ], "a": "2027-05-05T10:10:10Z", "b": 1 }"""
        assertThat(n(a, "application/json")).isEqualTo(n(b, "application/json"))
        assertThat(n("""{"a":1}""", "application/json")).isNotEqualTo(n("""{"a":2}""", "application/json"))
    }

    @Test
    fun `whitespace is collapsed`() {
        assertThat(n("a \n\t  b   c")).isEqualTo("a b c")
    }

    @Test
    fun `invalid json falls back to text`() {
        assertThat(n("{broken", "application/json")).isEqualTo("{broken")
    }
}
