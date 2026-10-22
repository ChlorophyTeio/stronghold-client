package top.ppparty.stronghold_client

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Bounded prefetch; one owner verifies/extracts/activates, workers only download. */
object ParallelDownloads {
    fun <T> run(items: List<T>, checkCancelled: () -> Unit, stop: () -> Unit,
                download: (T) -> Unit, consume: (T) -> Unit) {
        if (items.isEmpty()) return
        val pool = Executors.newFixedThreadPool(minOf(4, items.size)) { task ->
            Thread(task, "resource-download").apply { isDaemon = true }
        }
        val ready = ExecutorCompletionService<T>(pool)
        val pending = ArrayList<Future<T>>()
        var next = 0
        fun submit() {
            val item = items[next++]
            pending.add(ready.submit(Callable { checkCancelled(); download(item); item }))
        }
        try {
            repeat(minOf(4, items.size)) { submit() }
            repeat(items.size) {
                checkCancelled()
                var result = ready.poll(100, TimeUnit.MILLISECONDS)
                while (result == null) { checkCancelled(); result = ready.poll(100, TimeUnit.MILLISECONDS) }
                val item = try { result.get() } catch (error: ExecutionException) {
                    throw (error.cause ?: error)
                }
                consume(item)
                if (next < items.size) submit()
            }
        } catch (error: Throwable) {
            stop()
            throw error
        } finally {
            pending.forEach { it.cancel(true) }
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
