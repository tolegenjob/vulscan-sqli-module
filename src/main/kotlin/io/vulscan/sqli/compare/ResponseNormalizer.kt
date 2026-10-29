package io.vulscan.sqli.compare

import io.vulscan.kit.contracts.Json
import java.net.URLEncoder
import java.util.TreeMap

/**
 * Убирает из ответа то, что меняется от запроса к запросу (даты, идентификаторы, токены, отражённое значение),
 * чтобы различия между ответами отражали содержимое страницы, а не «шум».
 */
object ResponseNormalizer {
    private const val REFLECTED = "§"

    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?)?""")
    private val UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val UNIX_TIME = Regex("""\b1\d{9}(?:\d{3})?\b""")
    private val HEX_TOKEN = Regex("""\b[0-9a-fA-F]{16,}\b""")
    private val B64_TOKEN = Regex("""(?<![A-Za-z0-9+/_-])(?=[A-Za-z0-9+/_-]*\d)(?=[A-Za-z0-9+/_-]*[A-Za-z])[A-Za-z0-9+/_-]{16,}={0,2}""")
    private val INPUT_OR_META = Regex("""<(?:input|meta)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TOKEN_NAME = Regex("""csrf|xsrf|nonce|authenticity_token|requestverificationtoken""", RegexOption.IGNORE_CASE)
    private val VALUE_ATTR = Regex("""\b(value|content)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE)
    private val NONCE_ATTR = Regex("""\bnonce\s*=\s*("[^"]*"|'[^']*')""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")

    fun isJson(contentType: String?, body: String): Boolean =
        contentType?.contains("json", ignoreCase = true) == true ||
            body.trimStart().let { it.startsWith("{") || it.startsWith("[") } && contentType?.contains("html", ignoreCase = true) != true

    /** @param testValue значение, подставленное в проверяемый параметр: его отражение в ответе заменяется на `§`. */
    fun normalize(body: String, contentType: String?, testValue: String?): String {
        var text = maskReflected(body, testValue)
        if (isJson(contentType, text)) text = canonicalJson(text) ?: text
        text = INPUT_OR_META.replace(text) { m ->
            if (TOKEN_NAME.containsMatchIn(m.value)) VALUE_ATTR.replace(m.value) { v -> "${v.groupValues[1]}=\"§x\"" } else m.value
        }
        text = NONCE_ATTR.replace(text, "nonce=\"§x\"")
        text = ISO_DATE.replace(text, "§d")
        text = UUID.replace(text, "§u")
        text = UNIX_TIME.replace(text, "§t")
        text = HEX_TOKEN.replace(text, "§x")
        text = B64_TOKEN.replace(text, "§x")
        return SPACES.replace(text, " ").trim()
    }

    private fun maskReflected(body: String, value: String?): String {
        if (value.isNullOrEmpty()) return body
        val variants = listOf(
            value,
            value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"),
            value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#x27;"),
            URLEncoder.encode(value, Charsets.UTF_8),
            URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20"),
        ).distinct().sortedByDescending { it.length }
        var text = body
        for (variant in variants) text = text.replace(variant, REFLECTED)
        return text
    }

    /** Ключи отсортированы, пробелы убраны; не JSON → null. */
    private fun canonicalJson(text: String): String? = try {
        val parsed = Json.read(text, Any::class.java)
        buildString { render(parsed, this) }
    } catch (e: Exception) {
        null
    }

    private fun render(node: Any?, out: StringBuilder) {
        when (node) {
            null -> out.append("null")
            is Map<*, *> -> {
                val sorted = TreeMap<String, Any?>()
                node.forEach { (k, v) -> sorted[k.toString()] = v }
                out.append('{')
                var first = true
                for ((k, v) in sorted) {
                    if (!first) out.append(',')
                    first = false
                    out.append('"').append(k).append("\":")
                    render(v, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                node.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    render(v, out)
                }
                out.append(']')
            }
            is String -> out.append('"').append(node).append('"')
            else -> out.append(node.toString())
        }
    }
}
