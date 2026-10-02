package app.seanime.tv

/**
 * One worker owns both creation and disposal; callers only borrow a ready resource.
 * A close request also covers a resource whose creation has not returned yet.
 * It does not interrupt creation/disposal or promise that framework waits are cancellable.
 */
internal class OwnedAsyncTestResource<T : Any>(create: () -> T, dispose: (T) -> Unit) {
    private val lock = Object()
    private var resource: T? = null
    private var closeRequested = false
    private var launchFailure: Throwable? = null
    private var cleanupFailure: Throwable? = null
    @Volatile var launchCompleted = false
        private set
    @Volatile var cleanupCompleted = false
        private set

    val worker = Thread({
        val created = try {
            create()
        } catch (failure: Throwable) {
            synchronized(lock) {
                launchFailure = failure
                launchCompleted = true
                cleanupCompleted = true
            }
            return@Thread
        }
        synchronized(lock) {
            resource = created
            launchCompleted = true
            while (!closeRequested) {
                try { lock.wait() }
                catch (_: InterruptedException) { closeRequested = true }
            }
        }
        try {
            dispose(created)
        } catch (failure: Throwable) {
            synchronized(lock) { cleanupFailure = failure }
        } finally {
            synchronized(lock) {
                resource = null
                cleanupCompleted = true
            }
        }
    }, "OwnedAsyncTestResource").apply { isDaemon = true; start() }

    fun borrow(): T = synchronized(lock) {
        check(launchCompleted) { "Resource creation is still pending" }
        launchFailure?.let { throw it }
        check(!closeRequested) { "Resource cleanup already owns the result" }
        checkNotNull(resource)
    }

    fun requestClose() {
        synchronized(lock) {
            closeRequested = true
            lock.notifyAll()
        }
    }

    fun requireCleanupComplete() {
        synchronized(lock) {
            check(cleanupCompleted) { "Resource cleanup is still pending" }
            cleanupFailure?.let { throw it }
        }
    }
}
