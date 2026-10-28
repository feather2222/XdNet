package com.xd.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class XDNetworkClientTest {
    private lateinit var server: TestHttpServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = TestHttpServer()
        baseUrl = "http://127.0.0.1:${server.port}/api/"
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun get_encodesQueryAndReturnsResponse() {
        server.handle("/api/users") { request ->
            assertEquals("name=Jane%20Doe", request.uri.rawQuery)
            assertEquals("sdk-test", request.headers["x-client"])
            TestResponse(200, "{\"ok\":true}", "application/json; charset=utf-8")
        }
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .defaultHeader("X-Client", "sdk-test")
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()

        client.use {
            val response = it.get("users", mapOf("name" to "Jane Doe")).execute()
            assertTrue(response.isSuccessful)
            assertEquals("{\"ok\":true}", response.bodyString())
            assertEquals("application/json; charset=utf-8", response.header("Content-Type"))
        }
    }

    @Test
    fun post_sendsJsonBodyAndInterceptorHeader() {
        server.handle("/api/items") { request ->
            assertEquals("POST", request.method)
            assertEquals("Bearer token-123", request.headers["authorization"])
            assertEquals("{\"name\":\"phone\"}", request.body)
            TestResponse(201, "created")
        }
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .addInterceptor(BearerTokenInterceptor { "token-123" })
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()

        client.use {
            val response = it.post("items", RequestBody.json("{\"name\":\"phone\"}")).execute()
            assertEquals(201, response.code)
            assertEquals("created", response.bodyString())
        }
    }

    @Test
    fun get_retriesRetryableStatus() {
        val attempts = AtomicInteger()
        server.handle("/api/flaky") {
            if (attempts.incrementAndGet() < 3) TestResponse(503, "retry")
            else TestResponse(200, "done")
        }
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .retryPolicy(RetryPolicy(maxRetries = 2, initialDelayMillis = 0, maxDelayMillis = 0))
            .build()

        client.use {
            assertEquals("done", it.get("flaky").execute().bodyString())
            assertEquals(3, attempts.get())
        }
    }

    @Test(expected = NetworkException.ResponseTooLarge::class)
    fun responseSizeLimitIsEnforced() {
        server.handle("/api/large") { TestResponse(200, "12345") }
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .maxResponseBytes(4)
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()
        client.use { it.get("large").execute() }
    }

    @Test
    fun coroutineAndJson_encodeRequestAndDecodeResponse() = runBlocking {
        server.handle("/api/profile") { request ->
            assertEquals("{\"name\":\"Alice\"}", request.body)
            TestResponse(200, "{\"id\":7,\"name\":\"Alice\",\"futureField\":true}", "application/json")
        }
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .callbackExecutor(Executor { it.run() })
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()

        client.use {
            val profile: TestProfile = it.postJson("profile", CreateProfile("Alice"))
            assertEquals(TestProfile(7, "Alice"), profile)
        }
    }

    @Test
    fun coroutineCancellation_cancelsUnderlyingCall() = runBlocking {
        server.handle("/api/slow") {
            Thread.sleep(500)
            TestResponse(200, "late")
        }
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .callbackExecutor(Executor { it.run() })
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()
        client.use {
            val call = it.get("slow")
            val job = launch { call.await() }
            delay(50)
            job.cancelAndJoin()
            assertTrue(call.isCancelled)
        }
    }

    @Test
    fun refreshingToken_concurrent401sOnlyRefreshOnce() {
        server.handle("/api/private") { request ->
            if (request.headers["authorization"] == "Bearer new-token") TestResponse(200, "ok")
            else TestResponse(401, "expired")
        }
        val tokenReference = AtomicReference<String?>("old-token")
        val refreshes = AtomicInteger()
        val store = object : AccessTokenStore {
            override fun accessToken(): String? = tokenReference.get()
            override fun updateAccessToken(token: String?) { tokenReference.set(token) }
        }
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .addInterceptor(RefreshingTokenInterceptor(store) {
                refreshes.incrementAndGet()
                Thread.sleep(100)
                "new-token"
            })
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()
        val executor = Executors.newFixedThreadPool(6)

        try {
            val futures = List(6) { executor.submit<String> { client.get("private").execute().bodyString() } }
            assertTrue(futures.all { it.get() == "ok" })
            assertEquals(1, refreshes.get())
            assertEquals("new-token", tokenReference.get())
        } finally {
            executor.shutdownNow()
            client.close()
        }
    }

    @Test
    fun memoryCache_cacheFirstAvoidsSecondNetworkRequest() {
        val requests = AtomicInteger()
        server.handle("/api/cached") {
            TestResponse(
                code = 200,
                body = "value-${requests.incrementAndGet()}",
                headers = mapOf("Cache-Control" to "max-age=60")
            )
        }
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .cache(MemoryNetworkCache())
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()

        client.use {
            val network = it.get("cached").execute()
            val cached = it.get("cached").execute()
            assertEquals("value-1", network.bodyString())
            assertEquals("value-1", cached.bodyString())
            assertTrue(!network.fromCache)
            assertTrue(cached.fromCache)
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun diskCache_survivesAcrossClientInstances() {
        val directory = java.nio.file.Files.createTempDirectory("xd-network-cache").toFile()
        try {
            server.handle("/api/disk") {
                TestResponse(200, "persisted", headers = mapOf("Cache-Control" to "max-age=60"))
            }
            XDNetworkClient.Builder()
                .baseUrl(baseUrl)
                .cache(DiskNetworkCache(directory))
                .retryPolicy(RetryPolicy(maxRetries = 0))
                .build()
                .use { assertEquals("persisted", it.get("disk").execute().bodyString()) }

            XDNetworkClient.Builder()
                .baseUrl(baseUrl)
                .cache(DiskNetworkCache(directory), CachePolicy.CACHE_ONLY)
                .retryPolicy(RetryPolicy(maxRetries = 0))
                .build()
                .use {
                    val response = it.get("disk").execute()
                    assertTrue(response.fromCache)
                    assertEquals("persisted", response.bodyString())
                }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun transferProgress_reportsUploadAndDownloadCompletion() {
        val uploadSize = 96 * 1024
        val downloadBody = "x".repeat(80 * 1024)
        server.handle("/api/transfer") { TestResponse(200, downloadBody) }
        val uploads = CopyOnWriteArrayList<Triple<Long, Long, Boolean>>()
        val downloads = CopyOnWriteArrayList<Triple<Long, Long, Boolean>>()
        val request = NetworkRequest.Builder("transfer")
            .post(RequestBody.bytes(ByteArray(uploadSize)))
            .uploadProgress { transferred, total, done -> uploads += Triple(transferred, total, done) }
            .downloadProgress { transferred, total, done -> downloads += Triple(transferred, total, done) }
            .build()
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()

        client.use { it.newCall(request).execute() }
        val uploadFinal = uploads.last()
        val downloadFinal = downloads.last()
        assertEquals(uploadSize.toLong(), uploadFinal.first)
        assertEquals(uploadSize.toLong(), uploadFinal.second)
        assertTrue(uploadFinal.third)
        assertEquals(downloadBody.length.toLong(), downloadFinal.first)
        assertEquals(downloadBody.length.toLong(), downloadFinal.second)
        assertTrue(downloadFinal.third)
    }

    @Test
    fun fileUpload_streamsSelectedRangeFromDisk() {
        val source = java.nio.file.Files.createTempFile("xd-upload", ".bin").toFile()
        val content = "0123456789".repeat(32 * 1024)
        source.writeText(content)
        server.handle("/api/file-upload") { request ->
            assertEquals(content.substring(10, content.length - 15), request.body)
            TestResponse(200, "uploaded")
        }
        val progress = CopyOnWriteArrayList<Triple<Long, Long, Boolean>>()
        val body = RequestBody.file(source, offset = 10, length = source.length() - 25)
        val request = NetworkRequest.Builder("file-upload")
            .put(body)
            .uploadProgress { transferred, total, done -> progress += Triple(transferred, total, done) }
            .build()
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()
    
        try {
            assertEquals("uploaded", client.newCall(request).execute().bodyString())
            assertEquals(body.contentLength, progress.last().first)
            assertTrue(progress.last().third)
        } finally {
            client.close()
            source.delete()
        }
    }
    
    @Test
    fun fileDownload_resumesToDiskAndVerifiesSha256() {
        val content = "download-data-".repeat(24 * 1024)
        val existingSize = 23_000
        val destination = java.nio.file.Files.createTempFile("xd-download", ".bin").toFile()
        destination.writeText(content.substring(0, existingSize))
        server.handle("/api/file-download") { request ->
            assertEquals("bytes=$existingSize-", request.headers["range"])
            TestResponse(
                206,
                content.substring(existingSize),
                headers = mapOf("Content-Range" to "bytes $existingSize-${content.length - 1}/${content.length}")
            )
        }
        val progress = CopyOnWriteArrayList<Triple<Long, Long, Boolean>>()
        val request = NetworkRequest.Builder("file-download")
            .get()
            .downloadTo(
                destination,
                resume = true,
                checksum = FileChecksum(ChecksumAlgorithm.SHA256, sha256(content.toByteArray()))
            )
            .downloadProgress { transferred, total, done -> progress += Triple(transferred, total, done) }
            .build()
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()
    
        try {
            val response = client.newCall(request).execute()
            assertEquals(destination, response.downloadedFile)
            assertEquals(0, response.bodySize)
            assertEquals(content, destination.readText())
            assertEquals(existingSize.toLong(), progress.first().first)
            assertEquals(content.length.toLong(), progress.last().second)
            assertTrue(progress.last().third)
        } finally {
            client.close()
            destination.delete()
        }
    }
    
    @Test
    fun transferController_pausesAndResumesDownload() {
        val content = "paused-download".repeat(16 * 1024)
        server.handle("/api/paused-download") { TestResponse(200, content) }
        val destination = java.nio.file.Files.createTempFile("xd-paused", ".bin").toFile()
        val controller = TransferController().apply { pause() }
        val request = NetworkRequest.Builder("paused-download")
            .get()
            .downloadTo(destination, resume = false)
            .transferController(controller)
            .build()
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()
        val executor = Executors.newSingleThreadExecutor()
    
        try {
            val future = executor.submit<NetworkResponse> { client.newCall(request).execute() }
            Thread.sleep(100)
            assertFalse(future.isDone)
            controller.resume()
            assertEquals(content.length.toLong(), future.get(5, TimeUnit.SECONDS).downloadedFile?.length())
        } finally {
            executor.shutdownNow()
            client.close()
            destination.delete()
        }
    }
    
    @Test
    fun post_isNotRetriedByDefault() {
        val attempts = AtomicInteger()
        server.handle("/api/non-idempotent") {
            attempts.incrementAndGet()
            TestResponse(503, "retry")
        }
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .retryPolicy(RetryPolicy(maxRetries = 3, initialDelayMillis = 0, maxDelayMillis = 0))
            .build()
    
        client.use {
            assertEquals(503, it.post("non-idempotent", RequestBody.text("value")).execute().code)
            assertEquals(1, attempts.get())
        }
    }
    
    @Test
    fun callTimeout_limitsTheWholeRequest() {
        server.handle("/api/call-timeout") {
            Thread.sleep(500)
            TestResponse(200, "late")
        }
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .callTimeoutMillis(50)
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()
    
        try {
            client.get("call-timeout").execute()
            throw AssertionError("Expected NetworkException.Timeout")
        } catch (error: NetworkException.Timeout) {
            assertTrue(error.cause?.message?.contains("call timeout") == true)
        } finally {
            client.close()
        }
    }
    
    @Test
    fun cancelByTag_cancelsMatchingCallsOnly() {
        server.handle("/api/tagged") {
            Thread.sleep(500)
            TestResponse(200, "late")
        }
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val request = NetworkRequest.Builder("tagged").get().tag("video-detail").build()
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .callbackExecutor(Executor { it.run() })
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()
    
        try {
            client.newCall(request).enqueue { result ->
                failure.set(result.exceptionOrNull())
                completed.countDown()
            }
            Thread.sleep(50)
            client.cancelByTag("video-detail")
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(failure.get() is NetworkException.Cancelled)
        } finally {
            client.close()
        }
    }
    
    @Test
    fun asyncDispatcher_limitsConcurrentRequestsPerHost() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        server.handle("/api/limited") {
            val current = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, current) }
            try {
                Thread.sleep(100)
                TestResponse(200, "ok")
            } finally {
                active.decrementAndGet()
            }
        }
        val completed = CountDownLatch(6)
        val client = XDNetworkClient.Builder()
            .baseUrl(baseUrl)
            .maxConcurrentRequests(4)
            .maxRequestsPerHost(2)
            .callbackExecutor(Executor { it.run() })
            .retryPolicy(RetryPolicy(maxRetries = 0))
            .build()

        client.use { activeClient ->
            repeat(6) {
                activeClient.get("limited").enqueue { completed.countDown() }
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(2, maximum.get())
        }
    }
}

private fun sha256(value: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

@Serializable
private data class CreateProfile(val name: String)

@Serializable
private data class TestProfile(val id: Int, val name: String)

private data class TestRequest(
    val method: String,
    val uri: URI,
    val headers: Map<String, String>,
    val body: String
)

private data class TestResponse(
    val code: Int,
    val body: String,
    val contentType: String = "text/plain; charset=utf-8",
    val headers: Map<String, String> = emptyMap()
)

/** Minimal HTTP/1.1 loopback server used to keep the SDK tests dependency-free. */
private class TestHttpServer : AutoCloseable {
    private val socket = ServerSocket(0, 20)
    private val handlers = ConcurrentHashMap<String, (TestRequest) -> TestResponse>()
    private val acceptThread = Thread(::acceptLoop, "xd-test-http").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = socket.localPort

    fun handle(path: String, handler: (TestRequest) -> TestResponse) {
        handlers[path] = handler
    }

    override fun close() {
        socket.close()
        acceptThread.join(1_000)
    }

    private fun acceptLoop() {
        while (!socket.isClosed) {
            try {
                val connection = socket.accept()
                Thread({ serve(connection) }, "xd-test-http-client").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: SocketException) {
                return
            }
        }
    }

    private fun serve(connection: Socket) = connection.use { client ->
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = input.readAsciiLine().split(' ')
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = input.readAsciiLine()
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) String(input.readNBytes(contentLength), StandardCharsets.UTF_8) else ""
        val request = TestRequest(requestLine[0], URI(requestLine[1]), headers, body)
        val response = handlers[request.uri.path]?.invoke(request) ?: TestResponse(404, "not found")
        val responseBytes = response.body.toByteArray(StandardCharsets.UTF_8)
        val reason = if (response.code in 200..299) "OK" else "Error"
        val head = "HTTP/1.1 ${response.code} $reason\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            response.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } +
            "Content-Length: ${responseBytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.US_ASCII))
            write(responseBytes)
            flush()
        }
    }

    private fun BufferedInputStream.readAsciiLine(): String {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val value = read()
            if (value == -1 || value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }
}
