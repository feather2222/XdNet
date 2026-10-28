package com.xd.network

import kotlin.math.min
import kotlin.math.pow

data class RetryPolicy @JvmOverloads constructor(
    val maxRetries: Int = 2,
    val initialDelayMillis: Long = 300,
    val maxDelayMillis: Long = 3_000,
    val retryStatusCodes: Set<Int> = setOf(408, 429, 500, 502, 503, 504),
    val retryNonIdempotentRequests: Boolean = false
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(initialDelayMillis >= 0) { "initialDelayMillis must be >= 0" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis must be >= initialDelayMillis" }
    }

    internal fun canRetry(request: NetworkRequest): Boolean = retryNonIdempotentRequests ||
        request.method in setOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.DELETE)

    internal fun delayForAttempt(attempt: Int): Long = min(
        maxDelayMillis.toDouble(),
        initialDelayMillis * 2.0.pow(attempt.toDouble())
    ).toLong()
}
