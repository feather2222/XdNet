package com.xd.network

import java.util.ArrayDeque
import java.util.concurrent.ExecutorService

/** Limits queued asynchronous calls globally and per destination host. */
internal class RequestDispatcher(
    private val executor: ExecutorService,
    private val maxConcurrentRequests: Int,
    private val maxRequestsPerHost: Int
) {
    private data class Task(val host: String, val block: () -> Unit)

    private val ready = ArrayDeque<Task>()
    private val runningPerHost = mutableMapOf<String, Int>()
    private var running = 0

    fun enqueue(host: String, block: () -> Unit) {
        val promoted = synchronized(this) {
            ready.addLast(Task(host, block))
            promoteLocked()
        }
        execute(promoted)
    }

    private fun finished(task: Task) {
        val promoted = synchronized(this) {
            running--
            val hostCount = (runningPerHost[task.host] ?: 1) - 1
            if (hostCount == 0) runningPerHost.remove(task.host) else runningPerHost[task.host] = hostCount
            promoteLocked()
        }
        execute(promoted)
    }

    private fun promoteLocked(): List<Task> {
        if (running >= maxConcurrentRequests || ready.isEmpty()) return emptyList()
        val promoted = mutableListOf<Task>()
        val iterator = ready.iterator()
        while (iterator.hasNext() && running < maxConcurrentRequests) {
            val task = iterator.next()
            val hostCount = runningPerHost[task.host] ?: 0
            if (hostCount >= maxRequestsPerHost) continue
            iterator.remove()
            running++
            runningPerHost[task.host] = hostCount + 1
            promoted += task
        }
        return promoted
    }

    private fun execute(tasks: List<Task>) {
        tasks.forEach { task ->
            executor.execute {
                try {
                    task.block()
                } finally {
                    finished(task)
                }
            }
        }
    }
}
