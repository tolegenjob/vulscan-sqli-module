package io.vulscan.sqli.detect

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ErrorSignatureCatalogTest {

    companion object {
        @JvmStatic
        fun signatureIds(): List<String> = ErrorSignatureCatalog.all.map { it.id }
    }

    @Test
    fun `signature ids are unique`() {
        val ids = ErrorSignatureCatalog.all.map { it.id }
        assertThat(ids).doesNotHaveDuplicates()
    }

    @ParameterizedTest
    @MethodSource("signatureIds")
    fun `each signature matches its own reference text`(id: String) {
        val sample = ErrorSignatureCatalog.samples()[id]
        assertThat(sample).describedAs("sample for $id").isNotNull()
        assertThat(ErrorSignatureCatalog.matches(sample!!).map { it.id }).contains(id)
    }

    @Test
    fun `a documentation page merely quoting the MySQL error text is not matched`() {
        // lab-app /fp/sqli/sql-in-docs: цитирует фразу без продолжения "; check the manual...".
        val docsPage = "Пример из документации: если вы видите фразу \"You have an error in your SQL syntax\", " +
            "проверьте кавычки в запросе. Эта страница не выполняет запрос к БД вовсе."
        assertThat(ErrorSignatureCatalog.matches(docsPage)).isEmpty()
    }

    @Test
    fun `an ordinary product page is not matched by any signature`() {
        val page = "<html><body><h1>Каталог</h1><p>Клавиатура, мышь, монитор — учебные товары стенда.</p></body></html>"
        assertThat(ErrorSignatureCatalog.matches(page)).isEmpty()
    }
}
