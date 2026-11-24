package app.kitna.data

import android.app.Application
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Foreground editing and scheduled jobs use one book, auth session and sync lock. */
class KitnaRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = LocalStore(app)
    val cloud = CloudCoordinator(app, store, scope)
    private val loadMutex = Mutex()
    private var initialized = false
    suspend fun load() = loadMutex.withLock {
        if (!initialized) { store.load(); cloud.initialize(); initialized = true }
    }
    suspend fun syncInBackground(): Boolean {
        load()
        if (cloud.state.value.session == null || !ActivityNotifications(app).enabled()) return false
        return cloud.sync().isFailure
    }
    companion object {
        private var instance: KitnaRuntime? = null
        @Synchronized fun get(context: Context): KitnaRuntime {
            val app = context.applicationContext
            if (instance?.app !== app) { instance?.scope?.cancel(); instance?.store?.close(); instance = KitnaRuntime(app) }
            return requireNotNull(instance)
        }
        @Synchronized internal fun resetForTests() { instance?.scope?.cancel(); instance?.store?.close(); instance = null }
    }
}

class KitnaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationBackgroundSync.handler = { context -> KitnaRuntime.get(context).syncInBackground() }
    }
}
