package io.vulscan.sqli.detect

import io.vulscan.kit.contracts.DbmsHint
import org.yaml.snakeyaml.Yaml

/** Одна запись каталога; [dbms] = null — общая JDBC/ODBC-подпись, не участвует в определении СУБД. */
data class ErrorSignature(val id: String, val dbms: DbmsHint?, val pattern: Regex, val weight: Int)

/**
 * Регулярные выражения по типичным сообщениям об ошибках СУБД (Q-3). Загружается один раз из
 * `classpath:sqli/error-signatures.yml` — формат и инвариант (эталонный текст на каждую запись,
 * отсутствие дублей id) проверяет [ErrorSignatureCatalogTest].
 */
object ErrorSignatureCatalog {
    val all: List<ErrorSignature> by lazy { load() }

    fun matches(text: String): List<ErrorSignature> = all.filter { it.pattern.containsMatchIn(text) }

    private fun load(): List<ErrorSignature> {
        val stream = requireNotNull(javaClass.getResourceAsStream(RESOURCE_PATH)) {
            "classpath resource $RESOURCE_PATH not found"
        }
        val yaml = Yaml()
        @Suppress("UNCHECKED_CAST")
        val root = stream.use { yaml.load(it) as Map<String, Any> }
        @Suppress("UNCHECKED_CAST")
        val rows = root["signatures"] as List<Map<String, Any?>>
        val parsed = rows.map { row ->
            val id = row["id"] as String
            val dbms = (row["dbms"] as String?)?.let { DbmsHint.valueOf(it) }
            val weight = (row["weight"] as Number).toInt()
            ErrorSignature(id, dbms, Regex(row["regex"] as String), weight)
        }
        val duplicates = parsed.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate error-signature ids: $duplicates" }
        return parsed
    }

    /** Для [ErrorSignatureCatalogTest]: эталонный текст каждой записи из того же YAML. */
    internal fun samples(): Map<String, String> {
        val stream = requireNotNull(javaClass.getResourceAsStream(RESOURCE_PATH))
        val yaml = Yaml()
        @Suppress("UNCHECKED_CAST")
        val root = stream.use { yaml.load(it) as Map<String, Any> }
        @Suppress("UNCHECKED_CAST")
        val rows = root["signatures"] as List<Map<String, Any?>>
        return rows.associate { (it["id"] as String) to (it["sample"] as String) }
    }

    private const val RESOURCE_PATH = "/sqli/error-signatures.yml"
}
