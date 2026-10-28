package com.xd.network

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap

enum class CachePolicy {
    /** Always use the network and neither read nor write cache. */
    NETWORK_ONLY,

    /** Return any stored response, including stale data, or throw [NetworkException.CacheMiss]. */
    CACHE_ONLY,

    /** Return fresh cache first; otherwise use the network and update cache. */
    CACHE_FIRST,

    /** Use the network first and fall back to stored data on I/O errors or HTTP 5xx. */
    NETWORK_FIRST
}

/** Base type for SDK-managed response caches. */
sealed class NetworkCache {
    internal abstract fun get(key: String): CacheEntry?
    internal abstract fun put(key: String, entry: CacheEntry)
    internal abstract fun remove(key: String)
    abstract fun clear()
}

/** Thread-safe size-bounded LRU memory cache. */
class MemoryNetworkCache @JvmOverloads constructor(
    private val maxSizeBytes: Long = 4L * 1024L * 1024L
) : NetworkCache() {
    private val entries = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)
    private var sizeBytes = 0L

    init {
        require(maxSizeBytes > 0) { "maxSizeBytes must be > 0" }
    }

    @Synchronized
    override fun get(key: String): CacheEntry? = entries[key]?.deepCopy()

    @Synchronized
    override fun put(key: String, entry: CacheEntry) {
        val copy = entry.deepCopy()
        if (copy.sizeBytes > maxSizeBytes) return
        entries.put(key, copy)?.let { sizeBytes -= it.sizeBytes }
        sizeBytes += copy.sizeBytes
        val iterator = entries.entries.iterator()
        while (sizeBytes > maxSizeBytes && iterator.hasNext()) {
            sizeBytes -= iterator.next().value.sizeBytes
            iterator.remove()
        }
    }

    @Synchronized
    override fun remove(key: String) {
        entries.remove(key)?.let { sizeBytes -= it.sizeBytes }
    }

    @Synchronized
    override fun clear() {
        entries.clear()
        sizeBytes = 0
    }
}

/**
 * Thread-safe disk cache. Pass an app-private directory; cached payloads are not
 * encrypted and must not be used for secrets.
 */
class DiskNetworkCache @JvmOverloads constructor(
    private val directory: File,
    private val maxSizeBytes: Long = 50L * 1024L * 1024L
) : NetworkCache() {
    init {
        require(maxSizeBytes > 0) { "maxSizeBytes must be > 0" }
        require(directory.isDirectory || directory.mkdirs()) {
            "Unable to create cache directory: ${directory.absolutePath}"
        }
    }

    @Synchronized
    override fun get(key: String): CacheEntry? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use(::readEntry).also {
                file.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    @Synchronized
    override fun put(key: String, entry: CacheEntry) {
        if (entry.sizeBytes > maxSizeBytes) return
        val destination = fileFor(key)
        val temporary = File(directory, "${destination.name}.${Thread.currentThread().id}.tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { writeEntry(it, entry) }
            if (destination.exists() && !destination.delete()) throw IOException("Unable to replace cache entry")
            if (!temporary.renameTo(destination)) throw IOException("Unable to commit cache entry")
            evictIfNeeded()
        } catch (_: IOException) {
            temporary.delete()
        }
    }

    @Synchronized
    override fun remove(key: String) {
        fileFor(key).delete()
    }

    @Synchronized
    override fun clear() {
        cacheFiles().forEach(File::delete)
    }

    private fun evictIfNeeded() {
        val files = cacheFiles().sortedBy(File::lastModified)
        var total = files.sumOf(File::length)
        for (file in files) {
            if (total <= maxSizeBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun cacheFiles(): List<File> = directory.listFiles { file ->
        file.isFile && file.name.endsWith(CACHE_SUFFIX)
    }?.toList().orEmpty()

    private fun fileFor(key: String): File = File(directory, sha256(key) + CACHE_SUFFIX)

    private fun writeEntry(output: DataOutputStream, entry: CacheEntry) {
        output.writeInt(FORMAT_VERSION)
        output.writeLong(entry.storedAtMillis)
        output.writeLong(entry.expiresAtMillis)
        output.writeInt(entry.code)
        output.writeString(entry.message)
        output.writeInt(entry.headers.size)
        entry.headers.forEach { (name, values) ->
            output.writeString(name)
            output.writeInt(values.size)
            values.forEach { output.writeString(it) }
        }
        output.writeInt(entry.body.size)
        output.write(entry.body)
    }

    private fun readEntry(input: DataInputStream): CacheEntry {
        require(input.readInt() == FORMAT_VERSION) { "Unknown cache format" }
        val storedAt = input.readLong()
        val expiresAt = input.readLong()
        val code = input.readInt()
        val message = input.readString()
        val headerCount = input.readInt().checkedCount()
        val headers = linkedMapOf<String, List<String>>()
        repeat(headerCount) {
            val name = input.readString()
            val valueCount = input.readInt().checkedCount()
            headers[name] = List(valueCount) { input.readString() }
        }
        val bodyLength = input.readInt()
        require(bodyLength >= 0 && bodyLength <= maxSizeBytes) { "Invalid cache body length" }
        val body = ByteArray(bodyLength)
        input.readFully(body)
        return CacheEntry(code, message, headers, body, storedAt, expiresAt)
    }

    private fun Int.checkedCount(): Int = apply { require(this in 0..10_000) { "Invalid cache item count" } }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) { "Invalid cache string length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val FORMAT_VERSION = 1
        const val CACHE_SUFFIX = ".xd-cache"
        const val MAX_STRING_BYTES = 1024 * 1024
    }
}

internal data class CacheEntry(
    val code: Int,
    val message: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val storedAtMillis: Long,
    val expiresAtMillis: Long
) {
    val sizeBytes: Long get() = body.size.toLong() + headers.entries.sumOf { (name, values) ->
        name.length.toLong() + values.sumOf(String::length)
    } + message.length

    fun isFresh(nowMillis: Long): Boolean = nowMillis <= expiresAtMillis

    fun deepCopy(): CacheEntry = copy(
        headers = headers.mapValues { it.value.toList() },
        body = body.copyOf()
    )

    fun toResponse(request: NetworkRequest): NetworkResponse = NetworkResponse(
        request = request,
        code = code,
        message = message,
        headers = headers,
        body = body,
        durationMillis = 0,
        fromCache = true
    )
}
