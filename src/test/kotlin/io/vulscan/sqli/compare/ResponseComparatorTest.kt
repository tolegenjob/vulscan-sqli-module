package io.vulscan.sqli.compare

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ResponseComparatorTest {
    private fun page(items: List<Int>, stamp: String = "2026-10-22T10:00:00Z") =
        "<html><body><h1>Каталог товаров</h1><time>$stamp</time><ul>" +
            items.joinToString("") { "<li>Товар $it описание товара номер $it</li>" } +
            "</ul><footer>Магазин 2026</footer></body></html>"

    private fun sig(body: String, status: Int = 200, type: String = "text/html") = ResponseSignature.of(status, type, body, null)

    @Test
    fun `identical responses are fully similar`() {
        assertThat(ResponseComparator.similarity(sig(page(listOf(1, 2, 3))), sig(page(listOf(1, 2, 3))))).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `a page that differs only by a date stays above the stability threshold after normalisation`() {
        val a = sig(page(listOf(1, 2, 3), "2026-10-22T10:00:00Z"))
        val b = sig(page(listOf(1, 2, 3), "2026-10-23T18:30:00Z"))
        assertThat(ResponseComparator.similarity(a, b)).isGreaterThanOrEqualTo(0.98)
    }

    @Test
    fun `a different result list is below the distinguishability threshold`() {
        val full = sig(page(listOf(1, 2, 3, 4, 5)))
        val empty = sig("<html><body><h1>Каталог товаров</h1><time>2026-10-22T10:00:00Z</time><p>Ничего не найдено</p><footer>Магазин 2026</footer></body></html>")
        assertThat(ResponseComparator.similarity(full, empty)).isLessThan(0.90)
    }

    @Test
    fun `a different number of items is noticed even though the words are the same`() {
        val five = sig(page(listOf(1, 2, 3, 4, 5)))
        val four = sig(page(listOf(1, 2, 3, 4)))
        assertThat(ResponseComparator.similarity(five, four)).isLessThan(0.90)
    }

    @Test
    fun `status difference is reported separately from similarity`() {
        val ok = sig(page(listOf(1, 2)))
        val error = sig(page(listOf(1, 2)), status = 500)
        val result = ResponseComparator.compare(ok, error)
        assertThat(result.sameStatus).isFalse()
        assertThat(result.similarity).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `empty responses are equal`() {
        assertThat(ResponseComparator.similarity(sig(""), sig(""))).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `json responses are compared by keys and values`() {
        val a = sig("""{"items":[{"id":1,"name":"alpha"},{"id":2,"name":"beta"}]}""", type = "application/json")
        val b = sig("""{"items":[{"name":"beta","id":2},{"name":"alpha","id":1}]}""", type = "application/json")
        val c = sig("""{"items":[]}""", type = "application/json")
        assertThat(ResponseComparator.similarity(a, a)).isCloseTo(1.0, within(1e-9))
        assertThat(ResponseComparator.similarity(a, c)).isLessThan(0.90)
        assertThat(ResponseComparator.similarity(a, b)).isLessThan(1.0 + 1e-9)
    }
}
