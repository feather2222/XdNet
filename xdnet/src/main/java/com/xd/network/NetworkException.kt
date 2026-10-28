package com.xd.network

import java.io.IOException

sealed class NetworkException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class InvalidUrl(url: String, cause: Throwable? = null) :
        NetworkException("Invalid URL: $url", cause)

    class Timeout(cause: Throwable) : NetworkException("Network request timed out", cause)

    class Connection(cause: Throwable) : NetworkException("Unable to connect to the server", cause)

    class Cancelled : NetworkException("Network request was cancelled")

    class ResponseTooLarge(val limitBytes: Long) :
        NetworkException("Response exceeded the $limitBytes byte limit")

    class ChecksumMismatch(
        val expected: String,
        val actual: String
    ) : NetworkException("File checksum mismatch: expected $expected but was $actual")

    class HttpStatus(val response: NetworkResponse) :
        NetworkException("HTTP ${response.code} ${response.message}".trim())

    class Serialization(cause: Throwable) :
        NetworkException("Unable to encode or decode the JSON payload", cause)

    class CacheMiss : NetworkException("No cached response is available")

    class Unexpected(cause: Throwable) : NetworkException("Unexpected network error", cause)
}
