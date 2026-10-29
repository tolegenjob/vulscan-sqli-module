package io.vulscan.sqli.detect

import io.vulscan.kit.contracts.DbmsHint
import io.vulscan.sqli.plan.ValueType

/**
 * Пробы с задержкой (Q-5), закреплённые по точному тексту на СУБД и тип значения.
 * «Перебор всех СУБД» запрещён — проба строится только для уже известной [DbmsHint].
 *
 * Поддержаны только PostgreSQL и MySQL/MariaDB: их синтаксис позволяет встроить задержку прямо в
 * условие `WHERE` без `;`-стекового запроса (запрещён общими правилами модуля). MSSQL (`WAITFOR DELAY`)
 * требует именно стекового запроса, Oracle и SQLite не имеют безопасного инлайн-аналога — для них
 * time-based проверка не выполняется (это зафиксированное ограничение, а не упущение).
 */
object TimeProbeCatalog {
    private val SUPPORTED = setOf(DbmsHint.POSTGRESQL, DbmsHint.MYSQL)

    fun supports(dbms: DbmsHint): Boolean = dbms in SUPPORTED

    /** @param delaySeconds 0 — контрольное значение без реальной задержки. */
    fun probe(dbms: DbmsHint, valueType: ValueType, sample: String, delaySeconds: Int): String {
        require(supports(dbms)) { "time-based probes are not defined for $dbms" }
        val sleepExpr = when (dbms) {
            DbmsHint.POSTGRESQL -> "(SELECT 1 FROM pg_sleep($delaySeconds))"
            DbmsHint.MYSQL -> "SLEEP($delaySeconds)"
            else -> error("unreachable: $dbms not in SUPPORTED")
        }
        val condition = if (dbms == DbmsHint.POSTGRESQL) "1=$sleepExpr" else sleepExpr
        return when (valueType) {
            ValueType.STRING -> "$sample' AND $condition-- "
            ValueType.NUMERIC -> "$sample AND $condition"
        }
    }
}
