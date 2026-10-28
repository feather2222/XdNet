package com.xd.network

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections

/** An immutable HTTP request. Create one with [Builder]. */
class NetworkRequest private constructor(
    val path: String,
    val method: HttpMethod,
    headers: Map<String, String>,
    queryParameters: List<Pair<String, String>>,
    val body: RequestBody?,
    val tag: Any?,
    val skipTokenRefresh: Boolean,
    val cachePolicy: CachePolicy?,
    val cacheTtlMillis: Long?,
    val uploadProgressListener: ProgressListener?,
    val downloadProgressListener: ProgressListener?,
    val downloadOptions: DownloadOptions?,
    val transferController: TransferController?
) {
    val headers: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(headers))
    val queryParameters: List<Pair<String, String>> = Collections.unmodifiableList(queryParameters.toList())

    fun newBuilder(): Builder = Builder(path)
        .method(method, body)
        .also { builder ->
            headers.forEach(builder::header)
            queryParameters.forEach { (name, value) -> builder.query(name, value) }
            builder.tag(tag)
            builder.skipTokenRefresh(skipTokenRefresh)
            cachePolicy?.let(builder::cachePolicy)
            cacheTtlMillis?.let(builder::cacheTtlMillis)
            uploadProgressListener?.let(builder::uploadProgress)
            downloadProgressListener?.let(builder::downloadProgress)
            downloadOptions?.let { builder.downloadTo(it.destination, it.resume, it.checksum) }
            transferController?.let(builder::transferController)
        }

    internal fun encodedQuery(): String = queryParameters.joinToString("&") { (name, value) ->
        "${encode(name)}=${encode(value)}"
    }

    class Builder(private var path: String) {
        private var method = HttpMethod.GET
        private val headers = linkedMapOf<String, String>()
        private val queryParameters = mutableListOf<Pair<String, String>>()
        private var body: RequestBody? = null
        private var tag: Any? = null
        private var skipTokenRefresh = false
        private var cachePolicy: CachePolicy? = null
        private var cacheTtlMillis: Long? = null
        private var uploadProgressListener: ProgressListener? = null
        private var downloadProgressListener: ProgressListener? = null
        private var downloadOptions: DownloadOptions? = null
        private var transferController: TransferController? = null

        fun url(pathOrUrl: String) = apply { path = pathOrUrl }

        @JvmOverloads
        fun method(method: HttpMethod, body: RequestBody? = null) = apply {
            require(method.permitsRequestBody || body == null) { "$method does not permit a request body" }
            this.method = method
            this.body = body
        }

        fun get() = method(HttpMethod.GET)
        fun head() = method(HttpMethod.HEAD)
        fun post(body: RequestBody) = method(HttpMethod.POST, body)
        fun put(body: RequestBody) = method(HttpMethod.PUT, body)
        fun patch(body: RequestBody) = method(HttpMethod.PATCH, body)
        @JvmOverloads fun delete(body: RequestBody? = null) = method(HttpMethod.DELETE, body)

        fun header(name: String, value: String) = apply {
            validateHeader(name, value)
            headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(headers::remove)
            headers[name] = value
        }

        fun query(name: String, value: String) = apply {
            require(name.isNotBlank()) { "Query parameter name must not be blank" }
            queryParameters += name to value
        }

        fun tag(tag: Any?) = apply { this.tag = tag }

        /** Prevents bearer injection and 401 refresh, normally for the refresh endpoint itself. */
        fun skipTokenRefresh(value: Boolean = true) = apply { skipTokenRefresh = value }

        /** Overrides the client's cache policy for this request. */
        fun cachePolicy(value: CachePolicy) = apply { cachePolicy = value }

        /** Overrides cache freshness when the response has no Cache-Control max-age. */
        fun cacheTtlMillis(value: Long) = apply {
            require(value >= 0) { "cacheTtlMillis must be >= 0" }
            cacheTtlMillis = value
        }

        /** Reports request-body bytes written. The listener runs on the network thread. */
        fun uploadProgress(listener: ProgressListener) = apply { uploadProgressListener = listener }

        /** Reports response-body bytes read. The listener runs on the network thread. */
        fun downloadProgress(listener: ProgressListener) = apply { downloadProgressListener = listener }

        /** Streams the response directly to [destination], optionally continuing an existing file. */
        @JvmOverloads
        fun downloadTo(
            destination: java.io.File,
            resume: Boolean = true,
            checksum: FileChecksum? = null
        ) = apply {
            downloadOptions = DownloadOptions(destination, resume, checksum)
        }

        /** Allows an in-flight file transfer to be paused, resumed, or cancelled. */
        fun transferController(controller: TransferController) = apply {
            transferController = controller
        }

        fun build(): NetworkRequest {
            require(path.isNotBlank()) { "Request URL/path must not be blank" }
            require(downloadOptions == null || method == HttpMethod.GET) {
                "Streaming downloads require GET"
            }
            return NetworkRequest(
                path, method, headers, queryParameters, body, tag,
                skipTokenRefresh, cachePolicy, cacheTtlMillis,
                uploadProgressListener, downloadProgressListener,
                downloadOptions, transferController
            )
        }

        private fun validateHeader(name: String, value: String) {
            require(name.isNotBlank() && name.none { it <= ' ' || it >= '\u007f' }) {
                "Invalid header name: $name"
            }
            require(value.none { it == '\r' || it == '\n' }) { "Header value must not contain CR/LF" }
        }
    }

    companion object {
        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}
