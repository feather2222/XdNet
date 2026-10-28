package com.xd.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Lightweight dependency-free Android HTTP client. Instances are safe to share across threads. */
class XDNetworkClient private constructor(
    private val config: Config
) : Closeable {
    private val ownsDispatcher = config.ownsDispatcher
    private val requestDispatcher = RequestDispatcher(
        config.dispatcher,
        config.maxConcurrentRequests,
        config.maxRequestsPerHost
    )
    private val timeoutScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "xd-network-timeout").apply { isDaemon = true }
    }
    private val activeCalls = ConcurrentHashMap.newKeySet<RealNetworkCall>()

    fun newCall(request: NetworkRequest): NetworkCall = RealNetworkCall(this, request)

    @JvmOverloads
    fun get(path: String, query: Map<String, String> = emptyMap()): NetworkCall {
        val builder = NetworkRequest.Builder(path).get()
        query.forEach(builder::query)
        return newCall(builder.build())
    }

    fun post(path: String, body: RequestBody): NetworkCall =
        newCall(NetworkRequest.Builder(path).post(body).build())

    fun clearCache() = config.cache?.clear()

    /** Cancels queued and running requests whose tag equals [tag]. */
    fun cancelByTag(tag: Any) {
        activeCalls.filter { it.request.tag == tag }.forEach(RealNetworkCall::cancel)
    }

    fun cancelAll() = activeCalls.forEach(RealNetworkCall::cancel)

    override fun close() {
        cancelAll()
        timeoutScheduler.shutdownNow()
        if (config.ownsNetworkMonitor) config.networkMonitor?.close()
        if (ownsDispatcher) config.dispatcher.shutdown()
    }

    class Builder {
        private var baseUrl: String? = null
        private var connectTimeoutMillis = 10_000
        private var readTimeoutMillis = 15_000
        private var writeTimeoutMillis = 15_000
        private var callTimeoutMillis = 0L
        private var maxResponseBytes = 10L * 1024L * 1024L
        private var retryPolicy = RetryPolicy()
        private val interceptors = mutableListOf<Interceptor>()
        private val defaultHeaders = linkedMapOf<String, String>()
        private var dispatcher: ExecutorService? = null
        private var callbackExecutor: Executor? = null
        private var cache: NetworkCache? = null
        private var defaultCachePolicy = CachePolicy.CACHE_FIRST
        private var defaultCacheTtlMillis = 5 * 60 * 1_000L
        private var maxConcurrentRequests = 64
        private var maxRequestsPerHost = 5
        private var networkMonitor: NetworkMonitor? = null
        private var ownsNetworkMonitor = false
        private var networkRecoveryWaitMillis = 60_000L

        fun baseUrl(baseUrl: String) = apply { this.baseUrl = normalizeBaseUrl(baseUrl) }
        fun connectTimeoutMillis(value: Int) = apply {
            require(value > 0) { "connectTimeoutMillis must be > 0" }
            connectTimeoutMillis = value
        }
        fun readTimeoutMillis(value: Int) = apply {
            require(value > 0) { "readTimeoutMillis must be > 0" }
            readTimeoutMillis = value
        }
        fun writeTimeoutMillis(value: Int) = apply {
            require(value > 0) { "writeTimeoutMillis must be > 0" }
            writeTimeoutMillis = value
        }
        /** Zero disables the whole-call deadline. */
        fun callTimeoutMillis(value: Long) = apply {
            require(value >= 0) { "callTimeoutMillis must be >= 0" }
            callTimeoutMillis = value
        }
        fun maxResponseBytes(value: Long) = apply {
            require(value > 0) { "maxResponseBytes must be > 0" }
            maxResponseBytes = value
        }
        fun retryPolicy(value: RetryPolicy) = apply { retryPolicy = value }
        fun addInterceptor(interceptor: Interceptor) = apply { interceptors += interceptor }
        fun defaultHeader(name: String, value: String) = apply {
            // Reuse request validation and case-insensitive replacement.
            val validated = NetworkRequest.Builder("/").header(name, value).build().headers
            defaultHeaders.keys.firstOrNull { it.equals(name, true) }?.let(defaultHeaders::remove)
            defaultHeaders.putAll(validated)
        }
        fun dispatcher(value: ExecutorService) = apply { dispatcher = value }
        fun callbackExecutor(value: Executor) = apply { callbackExecutor = value }
        fun maxConcurrentRequests(value: Int) = apply {
            require(value > 0) { "maxConcurrentRequests must be > 0" }
            maxConcurrentRequests = value
        }
        fun maxRequestsPerHost(value: Int) = apply {
            require(value > 0) { "maxRequestsPerHost must be > 0" }
            maxRequestsPerHost = value
        }
        /** Waits for Android's default network before consuming the next retry attempt. */
        @JvmOverloads
        fun retryWhenNetworkAvailable(context: Context, maxWaitMillis: Long = 60_000) = apply {
            require(maxWaitMillis > 0) { "maxWaitMillis must be > 0" }
            if (ownsNetworkMonitor) networkMonitor?.close()
            networkMonitor = NetworkMonitor(context)
            ownsNetworkMonitor = true
            networkRecoveryWaitMillis = maxWaitMillis
        }
        /** Uses a caller-owned monitor; the client will not close it. */
        @JvmOverloads
        fun networkMonitor(value: NetworkMonitor, maxWaitMillis: Long = 60_000) = apply {
            require(maxWaitMillis > 0) { "maxWaitMillis must be > 0" }
            if (ownsNetworkMonitor) networkMonitor?.close()
            networkMonitor = value
            ownsNetworkMonitor = false
            networkRecoveryWaitMillis = maxWaitMillis
        }
        @JvmOverloads
        fun cache(
            value: NetworkCache,
            defaultPolicy: CachePolicy = CachePolicy.CACHE_FIRST,
            defaultTtlMillis: Long = 5 * 60 * 1_000L
        ) = apply {
            require(defaultTtlMillis >= 0) { "defaultTtlMillis must be >= 0" }
            cache = value
            defaultCachePolicy = defaultPolicy
            defaultCacheTtlMillis = defaultTtlMillis
        }

        fun build(): XDNetworkClient {
            val suppliedDispatcher = dispatcher
            val actualDispatcher = suppliedDispatcher ?: Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "xd-network").apply { isDaemon = true }
            }
            return XDNetworkClient(
                Config(
                    baseUrl = baseUrl,
                    connectTimeoutMillis = connectTimeoutMillis,
                    readTimeoutMillis = readTimeoutMillis,
                    writeTimeoutMillis = writeTimeoutMillis,
                    callTimeoutMillis = callTimeoutMillis,
                    maxResponseBytes = maxResponseBytes,
                    retryPolicy = retryPolicy,
                    interceptors = interceptors.toList(),
                    defaultHeaders = defaultHeaders.toMap(),
                    dispatcher = actualDispatcher,
                    callbackExecutor = callbackExecutor ?: MainThreadExecutor,
                    ownsDispatcher = suppliedDispatcher == null,
                    cache = cache,
                    defaultCachePolicy = defaultCachePolicy,
                    defaultCacheTtlMillis = defaultCacheTtlMillis,
                    maxConcurrentRequests = maxConcurrentRequests,
                    maxRequestsPerHost = maxRequestsPerHost,
                    networkMonitor = networkMonitor,
                    ownsNetworkMonitor = ownsNetworkMonitor,
                    networkRecoveryWaitMillis = networkRecoveryWaitMillis
                )
            )
        }

        private fun normalizeBaseUrl(value: String): String {
            val url = try { URL(value) } catch (error: MalformedURLException) {
                throw IllegalArgumentException("baseUrl must be an absolute HTTP(S) URL", error)
            }
            require(url.protocol == "http" || url.protocol == "https") { "baseUrl must use HTTP or HTTPS" }
            require(url.query == null && url.ref == null) { "baseUrl must not contain a query or fragment" }
            return if (value.endsWith('/')) value else "$value/"
        }
    }

    internal fun execute(call: RealNetworkCall, request: NetworkRequest): NetworkResponse {
        val cacheInterceptors = config.cache?.let { listOf(CacheInterceptor(config, it)) }.orEmpty()
        val allInterceptors = config.interceptors + cacheInterceptors +
            RetryInterceptor(config, call) + TransportInterceptor(config, call)
        return RealChain(allInterceptors, 0, request).proceed(request)
    }

    internal fun dispatch(request: NetworkRequest, block: () -> Unit) =
        requestDispatcher.enqueue(hostKey(request), block)
    internal fun callback(block: () -> Unit) = config.callbackExecutor.execute(block)

    internal fun register(call: RealNetworkCall) {
        activeCalls += call
    }

    internal fun finish(call: RealNetworkCall) {
        activeCalls -= call
    }

    internal fun scheduleTimeout(call: RealNetworkCall): ScheduledFuture<*>? =
        config.callTimeoutMillis.takeIf { it > 0 }?.let { timeout ->
            timeoutScheduler.schedule({ call.timeout("call") }, timeout, TimeUnit.MILLISECONDS)
        }

    internal fun scheduleWriteTimeout(call: RealNetworkCall): ScheduledFuture<*> =
        timeoutScheduler.schedule(
            { call.timeout("write") },
            config.writeTimeoutMillis.toLong(),
            TimeUnit.MILLISECONDS
        )

    private fun hostKey(request: NetworkRequest): String = runCatching {
        val requestUri = URI(request.path)
        val uri = if (requestUri.isAbsolute) {
            requestUri
        } else {
            config.baseUrl?.let { URI(it).resolve(requestUri) } ?: requestUri
        }
        val authority = uri.rawAuthority ?: return@runCatching "<relative>"
        "${uri.scheme.orEmpty().lowercase()}://${authority.lowercase()}"
    }.getOrDefault("<unknown>")

    internal data class Config(
        val baseUrl: String?,
        val connectTimeoutMillis: Int,
        val readTimeoutMillis: Int,
        val writeTimeoutMillis: Int,
        val callTimeoutMillis: Long,
        val maxResponseBytes: Long,
        val retryPolicy: RetryPolicy,
        val interceptors: List<Interceptor>,
        val defaultHeaders: Map<String, String>,
        val dispatcher: ExecutorService,
        val callbackExecutor: Executor,
        val ownsDispatcher: Boolean,
        val cache: NetworkCache?,
        val defaultCachePolicy: CachePolicy,
        val defaultCacheTtlMillis: Long,
        val maxConcurrentRequests: Int,
        val maxRequestsPerHost: Int,
        val networkMonitor: NetworkMonitor?,
        val ownsNetworkMonitor: Boolean,
        val networkRecoveryWaitMillis: Long
    )

    private object MainThreadExecutor : Executor {
        private val handler by lazy { Handler(Looper.getMainLooper()) }
        override fun execute(command: Runnable) {
            if (Looper.myLooper() == Looper.getMainLooper()) command.run() else handler.post(command)
        }
    }
}

internal class RealNetworkCall(
    private val client: XDNetworkClient,
    override val request: NetworkRequest
) : NetworkCall {
    private val executed = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpURLConnection?>()
    private val timeoutError = AtomicReference<SocketTimeoutException?>()

    override val isExecuted: Boolean get() = executed.get()
    override val isCancelled: Boolean get() = cancelled.get() ||
        request.transferController?.isCancelled == true

    override fun execute(): NetworkResponse {
        check(executed.compareAndSet(false, true)) { "This call has already been executed" }
        client.register(this)
        val timeout = client.scheduleTimeout(this)
        return try {
            checkCancelled()
            client.execute(this, request)
        } catch (error: Throwable) {
            throw mapError(error)
        } finally {
            completed.set(true)
            timeout?.cancel(false)
            activeConnection.set(null)
            client.finish(this)
        }
    }

    override fun enqueue(callback: NetworkCall.Callback) {
        if (!executed.compareAndSet(false, true)) {
            throw IllegalStateException("This call has already been executed")
        }
        client.register(this)
        val timeout = client.scheduleTimeout(this)
        client.dispatch(request) {
            val result = runCatching {
                checkCancelled()
                client.execute(this, request)
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(mapError(it)) }
            )
            completed.set(true)
            timeout?.cancel(false)
            activeConnection.set(null)
            client.finish(this)
            client.callback { callback.onResult(result) }
        }
    }

    override fun cancel() {
        cancelled.set(true)
        activeConnection.getAndSet(null)?.disconnect()
    }

    internal fun attach(connection: HttpURLConnection) {
        checkCancelled()
        activeConnection.set(connection)
        checkCancelled()
    }

    internal fun detach(connection: HttpURLConnection) {
        activeConnection.compareAndSet(connection, null)
    }

    internal fun timeout(kind: String) {
        if (completed.get()) return
        val error = SocketTimeoutException("$kind timeout")
        if (timeoutError.compareAndSet(null, error)) {
            activeConnection.getAndSet(null)?.disconnect()
        }
    }

    internal fun checkCancelled() {
        timeoutError.get()?.let { throw NetworkException.Timeout(it) }
        if (isCancelled) throw NetworkException.Cancelled()
    }

    internal fun awaitTransfer() {
        request.transferController?.awaitIfPaused(::checkCancelled) ?: checkCancelled()
    }

    internal fun <T> withWriteTimeout(block: () -> T): T {
        checkCancelled()
        val timeout = client.scheduleWriteTimeout(this)
        return try {
            block()
        } finally {
            timeout.cancel(false)
            checkCancelled()
        }
    }

    private fun mapError(error: Throwable): NetworkException {
        timeoutError.get()?.let { return NetworkException.Timeout(it) }
        return when {
            isCancelled -> NetworkException.Cancelled()
            error is NetworkException -> error
            error is SocketTimeoutException -> NetworkException.Timeout(error)
            error is UnknownHostException -> NetworkException.Connection(error)
            error is IOException -> NetworkException.Connection(error)
            else -> NetworkException.Unexpected(error as? Exception ?: Exception(error))
        }
    }
}

private class RealChain(
    private val interceptors: List<Interceptor>,
    private val index: Int,
    override val request: NetworkRequest
) : Interceptor.Chain {
    override fun proceed(request: NetworkRequest): NetworkResponse {
        check(index < interceptors.size) { "Interceptor chain exhausted" }
        return interceptors[index].intercept(RealChain(interceptors, index + 1, request))
    }
}

private class RetryInterceptor(
    private val config: XDNetworkClient.Config,
    private val call: RealNetworkCall
) : Interceptor {
    private val policy = config.retryPolicy

    override fun intercept(chain: Interceptor.Chain): NetworkResponse {
        if (!policy.canRetry(chain.request)) return chain.proceed(chain.request)
        var lastError: IOException? = null
        for (attempt in 0..policy.maxRetries) {
            call.checkCancelled()
            if (attempt > 0) waitBeforeRetry(policy.delayForAttempt(attempt - 1))
            try {
                val response = chain.proceed(chain.request)
                if (response.code !in policy.retryStatusCodes || attempt == policy.maxRetries) return response
            } catch (error: IOException) {
                if (error is NetworkException && error !is NetworkException.Connection) throw error
                lastError = error
                if (attempt == policy.maxRetries) throw error
            }
        }
        throw lastError ?: IOException("Retry failed")
    }

    private fun waitBeforeRetry(backoffMillis: Long) {
        val monitor = config.networkMonitor
        if (monitor != null && !monitor.currentStatus.isAvailable) {
            monitor.awaitAvailable(config.networkRecoveryWaitMillis, call::checkCancelled)
        }
        sleepCancellable(backoffMillis)
    }

    private fun sleepCancellable(delayMillis: Long) {
        var remaining = delayMillis
        while (remaining > 0) {
            call.checkCancelled()
            val slice = minOf(remaining, 100)
            try { Thread.sleep(slice) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw NetworkException.Cancelled()
            }
            remaining -= slice
        }
    }
}

internal class CacheInterceptor(
    private val config: XDNetworkClient.Config,
    private val cache: NetworkCache
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): NetworkResponse {
        val request = chain.request
        if (request.method != HttpMethod.GET || request.downloadOptions != null || hasSensitiveRequestHeaders(request)) {
            return chain.proceed(request)
        }

        val policy = request.cachePolicy ?: config.defaultCachePolicy
        if (policy == CachePolicy.NETWORK_ONLY) return chain.proceed(request)

        val key = cacheKey(request)
        val cached = runCatching { cache.get(key) }.getOrNull()
        val now = System.currentTimeMillis()

        when (policy) {
            CachePolicy.CACHE_ONLY -> return cached?.toResponse(request) ?: throw NetworkException.CacheMiss()
            CachePolicy.CACHE_FIRST -> if (cached?.isFresh(now) == true) return cached.toResponse(request)
            CachePolicy.NETWORK_FIRST, CachePolicy.NETWORK_ONLY -> Unit
        }

        return try {
            val response = chain.proceed(request)
            if (policy == CachePolicy.NETWORK_FIRST && response.code in 500..599 && cached != null) {
                cached.toResponse(request)
            } else {
                storeIfEligible(key, request, response, now)
                response
            }
        } catch (error: IOException) {
            if (policy == CachePolicy.NETWORK_FIRST && cached != null) cached.toResponse(request)
            else throw error
        }
    }

    private fun storeIfEligible(
        key: String,
        request: NetworkRequest,
        response: NetworkResponse,
        storedAtMillis: Long
    ) {
        if (!response.isSuccessful || response.fromCache || response.header("Set-Cookie") != null) return
        val cacheControl = response.header("Cache-Control").orEmpty()
        if (cacheControl.containsDirective("no-store") || cacheControl.containsDirective("no-cache")) {
            runCatching { cache.remove(key) }
            return
        }
        val ttlMillis = maxAgeMillis(cacheControl)
            ?: request.cacheTtlMillis
            ?: config.defaultCacheTtlMillis
        if (ttlMillis <= 0) return
        val expiresAt = if (Long.MAX_VALUE - storedAtMillis < ttlMillis) Long.MAX_VALUE
        else storedAtMillis + ttlMillis
        runCatching {
            cache.put(
                key,
                CacheEntry(
                    response.code,
                    response.message,
                    response.headers,
                    response.bytes(),
                    storedAtMillis,
                    expiresAt
                )
            )
        }
    }

    private fun hasSensitiveRequestHeaders(request: NetworkRequest): Boolean {
        val names = (config.defaultHeaders.keys + request.headers.keys).map(String::lowercase)
        return "authorization" in names || "cookie" in names
    }

    private fun cacheKey(request: NetworkRequest): String {
        val query = request.encodedQuery()
        val headers = (config.defaultHeaders + request.headers).entries
            .sortedBy { it.key.lowercase() }
            .joinToString("\n") { "${it.key.lowercase()}:${it.value}" }
        return buildString {
            append(config.baseUrl.orEmpty())
            append(request.path)
            if (query.isNotEmpty()) append(if ('?' in request.path) '&' else '?').append(query)
            append('\n').append(headers)
        }
    }

    private fun maxAgeMillis(cacheControl: String): Long? {
        val seconds = MAX_AGE.find(cacheControl)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return seconds.coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
    }

    private fun String.containsDirective(directive: String): Boolean =
        split(',').any { it.trim().equals(directive, ignoreCase = true) }

    private companion object {
        val MAX_AGE = Regex("(?:^|,)\\s*max-age\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
    }
}

private class TransportInterceptor(
    private val config: XDNetworkClient.Config,
    private val call: RealNetworkCall
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): NetworkResponse {
        val request = chain.request
        val url = resolveUrl(request)
        val connection = try { url.openConnection() as HttpURLConnection } catch (error: Exception) {
            throw NetworkException.InvalidUrl(url.toString(), error)
        }
        call.attach(connection)
        val startedAt = System.nanoTime()
        try {
            connection.requestMethod = request.method.name
            connection.connectTimeout = config.connectTimeoutMillis
            connection.readTimeout = config.readTimeoutMillis
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            config.defaultHeaders.forEach(connection::setRequestProperty)
            request.headers.forEach(connection::setRequestProperty)
            val resumeOffset = request.downloadOptions
                ?.takeIf { it.resume && it.destination.isFile }
                ?.destination
                ?.length()
                ?: 0L
            if (resumeOffset > 0 && request.headers.keys.none { it.equals("Range", true) }) {
                connection.setRequestProperty("Range", "bytes=$resumeOffset-")
            }
            request.body?.let { body ->
                connection.doOutput = true
                if (request.headers.keys.none { it.equals("Content-Type", true) }) {
                    connection.setRequestProperty("Content-Type", body.contentType)
                }
                connection.setFixedLengthStreamingMode(body.contentLength)
                val reporter = ProgressReporter(request.uploadProgressListener, body.contentLength)
                reporter.start()
                body.openStream().use { input ->
                    connection.outputStream.use { output ->
                        val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                        var transferred = 0L
                        while (true) {
                            call.awaitTransfer()
                            val count = input.read(buffer)
                            if (count == -1) break
                            call.withWriteTimeout { output.write(buffer, 0, count) }
                            transferred += count
                            reporter.update(transferred, done = transferred == body.contentLength)
                        }
                        if (transferred != body.contentLength) {
                            throw IOException("Request body ended after $transferred of ${body.contentLength} bytes")
                        }
                    }
                }
            }
            call.checkCancelled()
            val code = connection.responseCode
            var downloadedFile: java.io.File? = null
            val responseBody = if (request.method == HttpMethod.HEAD) ByteArray(0) else {
                val stream = if (code >= 400) connection.errorStream else connection.inputStream
                val options = request.downloadOptions
                if (options != null && code in 200..299) {
                    writeDownload(stream, options, code, resumeOffset, connection.contentLengthLong, request, call)
                    downloadedFile = options.destination
                    ByteArray(0)
                } else {
                    stream?.use {
                        readLimited(
                            it,
                            config.maxResponseBytes,
                            call,
                            request.downloadProgressListener,
                            connection.contentLengthLong
                        )
                    } ?: ByteArray(0).also {
                        ProgressReporter(request.downloadProgressListener, 0).start()
                    }
                }
            }
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key!! }
                .mapValues { it.value ?: emptyList() }
            return NetworkResponse(
                request = request,
                code = code,
                message = connection.responseMessage.orEmpty(),
                headers = headers,
                body = responseBody,
                durationMillis = (System.nanoTime() - startedAt) / 1_000_000,
                downloadedFile = downloadedFile
            )
        } finally {
            call.detach(connection)
            connection.disconnect()
        }
    }

    private fun resolveUrl(request: NetworkRequest): URL {
        val uri = try {
            val raw = URI(request.path)
            when {
                raw.isAbsolute -> raw
                config.baseUrl != null -> URI(config.baseUrl).resolve(raw)
                else -> throw NetworkException.InvalidUrl(request.path)
            }
        } catch (error: NetworkException) {
            throw error
        } catch (error: Exception) {
            throw NetworkException.InvalidUrl(request.path, error)
        }
        require(uri.scheme == "http" || uri.scheme == "https") { "Only HTTP(S) URLs are supported" }
        val encodedQuery = request.encodedQuery()
        val urlValue = if (encodedQuery.isEmpty()) {
            uri.toASCIIString()
        } else {
            val value = uri.toASCIIString()
            val fragmentIndex = value.indexOf('#').let { if (it == -1) value.length else it }
            val separator = if (uri.rawQuery.isNullOrEmpty()) "?" else "&"
            value.substring(0, fragmentIndex) + separator + encodedQuery + value.substring(fragmentIndex)
        }
        return try {
            URL(urlValue)
        } catch (error: Exception) {
            throw NetworkException.InvalidUrl(request.path, error)
        }
    }

    private fun readLimited(
        input: InputStream,
        limit: Long,
        call: RealNetworkCall,
        listener: ProgressListener?,
        contentLength: Long
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        val reporter = ProgressReporter(listener, contentLength.takeIf { it >= 0 } ?: -1)
        reporter.start()
        while (true) {
            call.awaitTransfer()
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            if (total > limit) throw NetworkException.ResponseTooLarge(limit)
            output.write(buffer, 0, count)
            reporter.update(total)
        }
        reporter.update(total, done = true)
        return output.toByteArray()
    }

private fun writeDownload(
    input: InputStream?,
    options: DownloadOptions,
    responseCode: Int,
    requestedOffset: Long,
    contentLength: Long,
    request: NetworkRequest,
    call: RealNetworkCall
) {
    val destination = options.destination
    val parent = destination.absoluteFile.parentFile
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
        throw IOException("Unable to create download directory: $parent")
    }
    val append = responseCode == HttpURLConnection.HTTP_PARTIAL && requestedOffset > 0
    val initialBytes = if (append) requestedOffset else 0L
    val totalBytes = if (contentLength >= 0) initialBytes + contentLength else -1L
    val reporter = ProgressReporter(request.downloadProgressListener, totalBytes)
    reporter.start(initialBytes)
    FileOutputStream(destination, append).buffered().use { output ->
        input?.use { source ->
            val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
            var transferred = initialBytes
            while (true) {
                call.awaitTransfer()
                val count = source.read(buffer)
                if (count == -1) break
                output.write(buffer, 0, count)
                transferred += count
                reporter.update(transferred)
            }
            reporter.update(transferred, done = true)
        } ?: reporter.update(initialBytes, done = true)
    }
    options.checksum?.let { checksum ->
        val actual = checksum.verify(destination)
        if (!actual.equals(checksum.expectedHex, ignoreCase = true)) {
            throw NetworkException.ChecksumMismatch(checksum.expectedHex, actual)
        }
    }
}

    private companion object {
        const val TRANSFER_BUFFER_SIZE = 16 * 1024
    }
}
