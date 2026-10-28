package com.xd.network

import java.io.File
import java.security.MessageDigest

enum class ChecksumAlgorithm(val messageDigestName: String) {
    MD5("MD5"),
    SHA256("SHA-256")
}

data class FileChecksum(
    val algorithm: ChecksumAlgorithm,
    val expectedHex: String
) {
    init {
        require(expectedHex.isNotBlank()) { "expectedHex must not be blank" }
        require(expectedHex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "expectedHex must contain hexadecimal characters only"
        }
    }

    internal fun verify(file: File): String {
        val digest = MessageDigest.getInstance(algorithm.messageDigestName)
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class DownloadOptions @JvmOverloads constructor(
    val destination: File,
    val resume: Boolean = true,
    val checksum: FileChecksum? = null
) {
    init {
        require(!destination.isDirectory) { "Download destination must be a file" }
    }
}

/** Controls a running upload or download without discarding its current connection. */
class TransferController {
    private val lock = Object()

    @Volatile
    var isPaused: Boolean = false
        private set

    @Volatile
    var isCancelled: Boolean = false
        private set

    fun pause() = synchronized(lock) {
        if (!isCancelled) isPaused = true
    }

    fun resume() = synchronized(lock) {
        if (!isCancelled) {
            isPaused = false
            lock.notifyAll()
        }
    }

    fun cancel() = synchronized(lock) {
        isCancelled = true
        isPaused = false
        lock.notifyAll()
    }

    internal fun awaitIfPaused(checkCall: () -> Unit) {
        synchronized(lock) {
            while (isPaused && !isCancelled) {
                checkCall()
                try {
                    lock.wait(100)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw NetworkException.Cancelled()
                }
            }
            if (isCancelled) throw NetworkException.Cancelled()
        }
        checkCall()
    }
}
