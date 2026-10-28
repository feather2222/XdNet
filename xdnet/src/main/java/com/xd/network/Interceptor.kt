package com.xd.network

import java.io.IOException
import java.net.HttpURLConnection

/** Intercepts requests and responses. An interceptor must call [Chain.proceed] to continue. */
fun interface Interceptor {
    @Throws(IOException::class)
    fun intercept(chain: Chain): NetworkResponse

    interface Chain {
        val request: NetworkRequest

        @Throws(IOException::class)
        fun proceed(request: NetworkRequest): NetworkResponse
    }
}

/** Adds a bearer access token at call time, so refreshed tokens are used automatically. */
class BearerTokenInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): NetworkResponse {
        val token = tokenProvider()
        val request = if (token.isNullOrBlank()) {
            chain.request
        } else {
            chain.request.newBuilder().header("Authorization", "Bearer $token").build()
        }
        return chain.proceed(request)
    }
}

/** Thread-safe storage used by [RefreshingTokenInterceptor]. */
interface AccessTokenStore {
    fun accessToken(): String?
    fun updateAccessToken(token: String?)
}

/** Performs the blocking refresh request and returns the new access token. */
fun interface TokenRefresher {
    @Throws(IOException::class)
    fun refreshAccessToken(failedToken: String?): String?
}

/**
 * Adds a bearer token and refreshes it once after HTTP 401. Concurrent 401s
 * share one refresh operation and then retry with the same new token.
 *
 * A refresh request made through the same client must set
 * [NetworkRequest.Builder.skipTokenRefresh] to avoid recursive refreshing.
 */
class RefreshingTokenInterceptor(
    private val tokenStore: AccessTokenStore,
    private val tokenRefresher: TokenRefresher
) : Interceptor {
    private val refreshLock = Any()

    override fun intercept(chain: Interceptor.Chain): NetworkResponse {
        if (chain.request.skipTokenRefresh) return chain.proceed(chain.request)

        val storedToken = tokenStore.accessToken()
        val explicitAuthorization = chain.request.headers.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.value
        val failedToken = explicitAuthorization?.bearerToken() ?: storedToken
        val authenticatedRequest = if (explicitAuthorization != null || storedToken.isNullOrBlank()) {
            chain.request
        } else {
            chain.request.newBuilder().header("Authorization", "Bearer $storedToken").build()
        }

        val response = chain.proceed(authenticatedRequest)
        if (response.code != HttpURLConnection.HTTP_UNAUTHORIZED) return response

        val retryToken = synchronized(refreshLock) {
            val currentToken = tokenStore.accessToken()
            if (!currentToken.isNullOrBlank() && currentToken != failedToken) {
                currentToken
            } else {
                tokenRefresher.refreshAccessToken(failedToken)?.takeIf(String::isNotBlank)?.also {
                    tokenStore.updateAccessToken(it)
                }
            }
        } ?: return response

        val retryRequest = authenticatedRequest.newBuilder()
            .header("Authorization", "Bearer $retryToken")
            .build()
        return chain.proceed(retryRequest)
    }

    private fun String.bearerToken(): String? =
        takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
            ?.takeIf(String::isNotBlank)
}

fun interface NetworkLogger {
    fun log(message: String)
}

/** Logs request metadata without logging bodies or sensitive header values. */
class LoggingInterceptor(private val logger: NetworkLogger) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): NetworkResponse {
        val request = chain.request
        val startedAt = System.nanoTime()
        logger.log("--> ${request.method} ${request.path}")
        return try {
            chain.proceed(request).also { response ->
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000
                logger.log("<-- ${response.code} ${request.method} ${request.path} (${elapsed}ms, ${response.bodySize} bytes)")
            }
        } catch (error: IOException) {
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000
            logger.log("<-- FAILED ${request.method} ${request.path} (${elapsed}ms): ${error.javaClass.simpleName}")
            throw error
        }
    }
}
