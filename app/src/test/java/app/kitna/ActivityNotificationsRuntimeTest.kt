package app.kitna

import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.kitna.core.*
import app.kitna.data.ActivityNotifications
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActivityNotificationsRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val account = "owner"
    private val notifications get() = ActivityNotifications(context)
    private fun snapshot(vararg events: RemoteActivity) = SharedSnapshot(RemoteGroup("remote", "Goa trip"), emptyList(), activity = events.toList(), myParticipantId = "self")
    @Before fun clear() { context.getSharedPreferences("activity_notifications", Context.MODE_PRIVATE).edit().clear().commit(); manager.cancelAll(); context.getSystemService(JobScheduler::class.java).cancelAll() }
    @Test fun baselineOwnEditsAndReplaysStaySilentWhileNewFriendActivityNotifies() {
        notifications.setEnabled(true);notifications.setActiveAccount(account)
        val old = RemoteActivity("old", "Old bill", 100, "friend")
        notifications.onAcceptedSnapshot(account,"local",snapshot(old));assertEquals(0,manager.activeNotifications.size)
        val own = RemoteActivity("own", "My bill", 200, account)
        notifications.onAcceptedSnapshot(account,"local",snapshot(old,own));assertEquals(0,manager.activeNotifications.size)
        val friend = RemoteActivity("friend", "Added taxi", 200, "friend")
        notifications.onAcceptedSnapshot(account,"local",snapshot(old,own,friend));assertEquals(1,manager.activeNotifications.size)
        assertEquals("Goa trip",manager.activeNotifications.single().notification.extras.getString("android.title"))
        manager.cancelAll();notifications.onAcceptedSnapshot(account,"local",snapshot(old,own,friend));assertEquals(0,manager.activeNotifications.size)
    }
    @Test fun logoutCancelsJobsAndIgnoresStaleDeliveryUntilANewBaseline() {
        notifications.setEnabled(true);notifications.setActiveAccount(account)
        assertNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(ActivityNotifications.JOB))
        notifications.onAcceptedSnapshot(account,"local",snapshot())
        notifications.setActiveAccount(null)
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(ActivityNotifications.JOB))
        notifications.onAcceptedSnapshot(account,"local",snapshot(RemoteActivity("late","Late",300,"friend")))
        assertEquals(0,manager.activeNotifications.size)
        notifications.setActiveAccount(account)
        notifications.onAcceptedSnapshot(account,"local",snapshot(RemoteActivity("late","Late",300,"friend")))
        assertEquals(0,manager.activeNotifications.size)
    }
    @Test fun disabledUpdatesAndImportedEventsDoNotBecomeLaterNotificationFloods() {
        notifications.setActiveAccount(account)
        notifications.onAcceptedSnapshot(account,"local",snapshot(RemoteActivity("old","Old",100,"friend")))
        notifications.onAcceptedSnapshot(account,"local",snapshot(RemoteActivity("new","New",200,"friend"),RemoteActivity("import","Imported future",99999,null)))
        notifications.setEnabled(true)
        notifications.onAcceptedSnapshot(account,"local",snapshot(RemoteActivity("new","New",200,"friend")))
        assertEquals(0,manager.activeNotifications.size)
        notifications.onAcceptedSnapshot(account,"local",snapshot(RemoteActivity("next","Next",300,"friend")))
        assertEquals(1,manager.activeNotifications.size)
        notifications.setEnabled(false);assertEquals(0,manager.activeNotifications.size)
    }
}
