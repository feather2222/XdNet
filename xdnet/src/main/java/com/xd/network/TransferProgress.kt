package com.xd.network

/** Receives transfer progress on the SDK network thread. */
fun interface ProgressListener {
    /** [totalBytes] is -1 when the server did not provide a length. */
    fun onProgress(bytesTransferred: Long, totalBytes: Long, done: Boolean)
}

internal class ProgressReporter(
    private val listener: ProgressListener?,
    private val totalBytes: Long,
    private val minIntervalMillis: Long = 100
) {
    private var lastReportedAt = 0L

    fun start(bytesTransferred: Long = 0) = notify(bytesTransferred, done = totalBytes == bytesTransferred)

    fun update(bytesTransferred: Long, done: Boolean = false) {
        val now = System.currentTimeMillis()
        if (done || now - lastReportedAt >= minIntervalMillis) {
            notify(bytesTransferred, done)
            lastReportedAt = now
        }
    }

    private fun notify(bytesTransferred: Long, done: Boolean) {
        listener?.let { runCatching { it.onProgress(bytesTransferred, totalBytes, done) } }
    }
}
