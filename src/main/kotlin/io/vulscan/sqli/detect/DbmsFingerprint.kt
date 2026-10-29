package io.vulscan.sqli.detect

import io.vulscan.kit.contracts.DbmsHint

/** Определяет СУБД по сумме весов совпавших сигнатур; при равенстве весов двух СУБД — `null` (неопределено). */
object DbmsFingerprint {
    fun of(matches: List<ErrorSignature>): DbmsHint? {
        val totals = HashMap<DbmsHint, Int>()
        matches.forEach { sig -> sig.dbms?.let { totals[it] = (totals[it] ?: 0) + sig.weight } }
        if (totals.isEmpty()) return null
        val max = totals.values.max()
        val leaders = totals.filterValues { it == max }.keys
        return leaders.singleOrNull()
    }
}
