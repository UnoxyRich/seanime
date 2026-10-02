package app.seanime.tv.data

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.OkHttpClient

/** Owns one player's HTTP clients; Media3 continues to own and close their response bodies. */
internal class NativePlayerHttpClients(
    private val cleanupExecutor: Executor = Dispatchers.IO.asExecutor(),
) : Closeable {
    private val lock = Any()
    @Volatile private var retired = false
    private val clients = mutableListOf<OkHttpClient>()
    // Dispatcher.runningCalls excludes responses already handed to Media3's loading thread.
    private val calls = mutableSetOf<Call>()

    fun createClient(configure: OkHttpClient.Builder.() -> Unit): OkHttpClient = synchronized(lock) {
        check(!retired) { "Player HTTP clients are retired" }
        val pool = ConnectionPool()
        OkHttpClient.Builder().apply(configure)
            // Every owner has independent resources, including during rapid player replacement.
            .connectionPool(pool).dispatcher(Dispatcher())
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    synchronized(lock) { if (!retired) calls.add(call) }
                }
                override fun callEnd(call: Call) = finished(call)
                override fun callFailed(call: Call, ioe: IOException) = finished(call)
                private fun finished(call: Call) { synchronized(lock) { calls.remove(call) } }
                override fun connectionReleased(call: Call, connection: Connection) {
                    // Media3 release can return before a loader has closed its response body.
                    if (retired) cleanupExecutor.execute { pool.evictAll() }
                }
            })
            .addInterceptor { chain ->
                // Also reject calls created before retirement but executed after it.
                if (retired) throw IOException("Player HTTP clients are retired")
                chain.proceed(chain.request())
            }
            .build().also { clients.add(it) }
    }

    override fun close() {
        val (ownedClients, ownedCalls) = synchronized(lock) {
            if (retired) return
            retired = true
            (clients.toList() to calls.toList()).also { clients.clear(); calls.clear() }
        }
        // TLS pool eviction can write close-notify. Keep it off the Activity lifecycle thread.
        // Shared IO survives Activity destruction and only captures this retired generation.
        cleanupExecutor.execute {
            ownedCalls.forEach { it.cancel() }
            ownedClients.forEach { client ->
                try {
                    client.dispatcher.cancelAll()
                    client.connectionPool.evictAll()
                } finally {
                    client.dispatcher.executorService.shutdown()
                }
            }
        }
    }
}
