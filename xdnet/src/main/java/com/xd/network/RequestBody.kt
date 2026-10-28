package com.xd.network

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A repeatable request body. File bodies are opened lazily and never copied into memory. */
class RequestBody private constructor(
    val contentType: String,
    val contentLength: Long,
    private val source: () -> InputStream
) {
    internal fun openStream(): InputStream = source()

    /** Reads the complete body. Avoid this method for large file bodies. */
    fun bytes(): ByteArray = openStream().use(InputStream::readBytes)

    companion object {
        @JvmStatic
        @JvmOverloads
        fun text(value: String, contentType: String = "text/plain; charset=utf-8") =
            byteBody(value.toByteArray(StandardCharsets.UTF_8), contentType)

        @JvmStatic
        fun json(value: String) = text(value, "application/json; charset=utf-8")

        @JvmStatic
        fun bytes(value: ByteArray, contentType: String = "application/octet-stream") =
            byteBody(value.copyOf(), contentType)

        /**
         * Streams a file or a repeatable byte range from disk. For resumable uploads the backend
         * must accept the matching Content-Range/offset protocol.
         */
        @JvmStatic
        @JvmOverloads
        fun file(
            file: File,
            contentType: String = "application/octet-stream",
            offset: Long = 0,
            length: Long = file.length() - offset
        ): RequestBody {
            require(file.isFile) { "Upload source must be an existing file: $file" }
            require(offset >= 0 && offset <= file.length()) { "offset is outside the file" }
            require(length >= 0 && length <= file.length() - offset) { "length is outside the file" }
            return RequestBody(contentType, length) {
                LimitedInputStream(file.inputStream().buffered().also { input ->
                    var remaining = offset
                    while (remaining > 0) {
                        val skipped = input.skip(remaining)
                        if (skipped <= 0) {
                            input.close()
                            throw IllegalStateException("Unable to seek to file offset $offset")
                        }
                        remaining -= skipped
                    }
                }, length)
            }
        }

        @JvmStatic
        fun form(fields: Map<String, String>): RequestBody {
            val encoded = fields.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
            return text(encoded, "application/x-www-form-urlencoded; charset=utf-8")
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())

        private fun byteBody(value: ByteArray, contentType: String) =
            RequestBody(contentType, value.size.toLong()) { ByteArrayInputStream(value) }
    }
}

private class LimitedInputStream(
    private val delegate: InputStream,
    private var remaining: Long
) : InputStream() {
    override fun read(): Int {
        if (remaining == 0L) return -1
        val value = delegate.read()
        if (value != -1) remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return -1
        val count = delegate.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) remaining -= count
        return count
    }

    override fun close() = delegate.close()
}
