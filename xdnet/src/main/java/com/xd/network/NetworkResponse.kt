package com.xd.network

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Collections

/** Complete HTTP response. Non-2xx responses are returned normally with [isSuccessful] false. */
class NetworkResponse internal constructor(
    val request: NetworkRequest,
    val code: Int,
    val message: String,
    headers: Map<String, List<String>>,
    body: ByteArray,
    val durationMillis: Long,
    val fromCache: Boolean = false,
    /** Set when [NetworkRequest.Builder.downloadTo] streamed the response to disk. */
    val downloadedFile: File? = null
) {
    val headers: Map<String, List<String>> = Collections.unmodifiableMap(
        headers.mapValues { Collections.unmodifiableList(it.value.toList()) }
    )
    private val content = body.copyOf()

    val isSuccessful: Boolean get() = code in 200..299
    val bodySize: Int get() = content.size

    fun bytes(): ByteArray = content.copyOf()

    @JvmOverloads
    fun bodyString(charset: Charset = charsetFromContentType() ?: StandardCharsets.UTF_8): String =
        content.toString(charset)

    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.firstOrNull()

    private fun charsetFromContentType(): Charset? {
        val contentType = header("Content-Type") ?: return null
        val charsetName = contentType.split(';')
            .map(String::trim)
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"')
            ?: return null
        return runCatching { Charset.forName(charsetName) }.getOrNull()
    }
}
