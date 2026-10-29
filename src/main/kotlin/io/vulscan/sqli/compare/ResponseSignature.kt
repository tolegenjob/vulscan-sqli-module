package io.vulscan.sqli.compare

/**
 * Компактное представление ответа для сравнения: статус, тип, длина нормализованного текста,
 * множество «слов» длиной ≥ 3 и хэш структуры (последовательность HTML-тегов или JSON-ключей).
 */
class ResponseSignature(
    val status: Int,
    val contentType: String?,
    val normalizedLength: Int,
    val tokenSet: Set<String>,
    val structureHash: Int,
) {
    companion object {
        private val TAG = Regex("""<([a-zA-Z][a-zA-Z0-9-]*)""")
        private val JSON_KEY = Regex(""""([^"]+)":""")
        private val SCRIPT_OR_STYLE = Regex("""<(script|style)\b[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
        private val ANY_TAG = Regex("""<[^>]*>""")
        private val WORD = Regex("""[\p{L}\p{N}_]{3,}""")

        fun of(status: Int, contentType: String?, body: String, testValue: String?): ResponseSignature {
            val normalized = ResponseNormalizer.normalize(body, contentType, testValue)
            val json = ResponseNormalizer.isJson(contentType, body)
            val text = if (json) normalized else ANY_TAG.replace(COMMENT.replace(SCRIPT_OR_STYLE.replace(normalized, " "), " "), " ")
            val tokens = WORD.findAll(text).map { it.value.lowercase() }.toSet()
            val structure = if (json) JSON_KEY.findAll(normalized).map { it.groupValues[1] }.toList() else TAG.findAll(normalized).map { it.groupValues[1].lowercase() }.toList()
            return ResponseSignature(status, contentType?.substringBefore(';')?.trim()?.lowercase(), normalized.length, tokens, structure.hashCode())
        }
    }
}
