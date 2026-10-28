package com.xd.network

import java.io.IOException

interface NetworkCall {
    val request: NetworkRequest
    val isExecuted: Boolean
    val isCancelled: Boolean

    /** Executes on the current thread. Never call this from the Android main thread. */
    @Throws(IOException::class)
    fun execute(): NetworkResponse

    /** Executes on the client's background dispatcher and posts the result to its callback executor. */
    fun enqueue(callback: Callback)

    fun cancel()

    fun interface Callback {
        fun onResult(result: Result<NetworkResponse>)
    }
}
