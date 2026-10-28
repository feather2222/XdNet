package com.xd.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

/**
 * Suspends until this call completes. Cancelling the coroutine also disconnects
 * the underlying HTTP connection.
 */
suspend fun NetworkCall.await(): NetworkResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue { result ->
        result.fold(
            onSuccess = { response ->
                continuation.resume(response) { _, _, _ -> }
            },
            onFailure = { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        )
    }
}
