package io.vulscan.sqli.http

import io.vulscan.kit.net.ProbeRequest
import io.vulscan.kit.net.ProbeResponse

/** Один выполненный запрос с декодированным телом; [value] — подставленное в параметр значение. */
class Exchange(val value: String, val request: ProbeRequest, val response: ProbeResponse, val body: String) {
    val status: Int get() = response.status
    val elapsedMs: Long get() = response.elapsedMs
}
