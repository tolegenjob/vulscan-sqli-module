package io.vulscan.sqli.compare

import kotlin.math.abs
import kotlin.math.max

data class ComparisonResult(val similarity: Double, val sameStatus: Boolean, val sameContentType: Boolean)

object ResponseComparator {
    /** 0.5·Jaccard(слова) + 0.3·(структура совпала) + 0.2·(1 − |Δдлины| / max длина). Статус в оценку не входит. */
    fun similarity(a: ResponseSignature, b: ResponseSignature): Double {
        val union = a.tokenSet.size + b.tokenSet.size - a.tokenSet.intersect(b.tokenSet).size
        val jaccard = if (union == 0) 1.0 else a.tokenSet.intersect(b.tokenSet).size.toDouble() / union
        val structure = if (a.structureHash == b.structureHash) 1.0 else 0.0
        val longest = max(a.normalizedLength, b.normalizedLength)
        val length = if (longest == 0) 1.0 else 1.0 - abs(a.normalizedLength - b.normalizedLength).toDouble() / longest
        return 0.5 * jaccard + 0.3 * structure + 0.2 * length
    }

    fun compare(a: ResponseSignature, b: ResponseSignature) =
        ComparisonResult(similarity(a, b), a.status == b.status, a.contentType == b.contentType)
}
