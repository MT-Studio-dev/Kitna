package app.kitna.data

import android.Manifest
import android.app.*
import android.app.job.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import app.kitna.MainActivity
import app.kitna.R
import app.kitna.core.SharedSnapshot
import kotlinx.coroutines.*

/** All delivery and cursor changes are serialized across foreground and scheduled sync. */
class ActivityNotifications(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("activity_notifications", Context.MODE_PRIVATE)
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    fun enabled() = preferences.getBoolean("enabled", false)
    fun permissionGranted(): Boolean = (Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && manager.areNotificationsEnabled()
    fun setEnabled(value: Boolean) = synchronized(lock) {
        preferences.edit().putBoolean("enabled", value).commit()
        if (!value) manager.cancelAll()
        refreshSchedule()
    }
    fun setActiveAccount(accountId: String?) = synchronized(lock) {
        if (preferences.getString("account", null) != accountId) {
            // A sign-in starts a new baseline. Never replay activity from a prior session.
            val edit = preferences.edit()
            preferences.all.keys.filter { it.startsWith("cursor:") }.forEach(edit::remove)
            edit.putString("account", accountId).commit()
            manager.cancelAll()
        }
        refreshSchedule()
    }
    fun cancelGroup(accountId: String, localGroupId: String) = synchronized(lock) {
        manager.cancel("$accountId:$localGroupId", 1)
        preferences.edit().remove("cursor:$accountId:$localGroupId:time").remove("cursor:$accountId:$localGroupId:ids").commit()
    }
    fun onAcceptedSnapshot(accountId: String, localGroupId: String, snapshot: SharedSnapshot) = synchronized(lock) {
        if (preferences.getString("account", null) != accountId) return@synchronized
        val key = "cursor:$accountId:$localGroupId"
        val activity = snapshot.activity.filter { it.actorId != null }
        val latest = activity.maxOfOrNull { it.time } ?: 0L
        val old = preferences.getLong("$key:time", -1L)
        val seen = preferences.getStringSet("$key:ids", emptySet()).orEmpty()
        val unseen = if (old < 0) emptyList() else activity.filter { it.actorId != accountId && (it.time > old || it.time == old && it.id !in seen) }
        val high = maxOf(latest, old, 0L)
        val ids = activity.filter { it.time == high }.map { it.id }.toSet() + if (high == old) seen else emptySet()
        // Advance even while disabled, so enabling never produces a history flood.
        check(preferences.edit().putLong("$key:time", high).putStringSet("$key:ids", ids).commit())
        if (unseen.isNotEmpty() && enabled() && permissionGranted()) {
            ensureChannel()
            val intent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(Uri.Builder().scheme("app.kitna").authority("group").appendQueryParameter("id", localGroupId).build())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(app, localGroupId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val public = Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Kitna").setContentText("Your shared groups have new activity.").build()
            val notification = Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(snapshot.group.name).setContentText(if (unseen.size == 1) "New shared-group activity" else "${unseen.size} new group updates")
                .setContentIntent(pending).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
                .setCategory(Notification.CATEGORY_SOCIAL).setOnlyAlertOnce(true).build()
            manager.notify("$accountId:$localGroupId", 1, notification)
        }
    }
    fun refreshSchedule() = synchronized(lock) {
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (enabled() && permissionGranted() && preferences.getString("account", null) != null) {
            ensureChannel()
            if (scheduler.getPendingJob(JOB) == null) scheduler.schedule(JobInfo.Builder(JOB, ComponentName(app, SharedActivityJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
        } else scheduler.cancel(JOB)
    }
    private fun ensureChannel() { manager.createNotificationChannel(NotificationChannel(CHANNEL, "Shared group activity", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Updates from friends in your shared Kitna groups"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE }) }
    companion object { private val lock = Any(); const val CHANNEL = "shared_group_activity"; const val JOB = 6206 }
}

object NotificationBackgroundSync { var handler: (suspend (Context) -> Boolean)? = null }

class SharedActivityJobService : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val retry = try { withTimeoutOrNull(90_000) { NotificationBackgroundSync.handler?.invoke(applicationContext) ?: false } ?: true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { true }
            jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); work = null; return true }
    override fun onDestroy() { work?.cancel(); super.onDestroy() }
}
