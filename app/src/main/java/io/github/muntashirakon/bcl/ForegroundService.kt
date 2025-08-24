package io.github.muntashirakon.bcl

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.muntashirakon.bcl.Constants.INTENT_DISABLE_ACTION
import io.github.muntashirakon.bcl.Constants.NOTIFICATION_LIVE
import io.github.muntashirakon.bcl.Constants.NOTIF_CHARGE
import io.github.muntashirakon.bcl.Constants.NOTIF_MAINTAIN
import io.github.muntashirakon.bcl.Constants.SETTINGS
import io.github.muntashirakon.bcl.activities.MainActivity
import io.github.muntashirakon.bcl.receivers.BatteryReceiver
import io.github.muntashirakon.bcl.receivers.ControlBatteryChargeReceiver
import io.github.muntashirakon.bcl.settings.PrefsFragment
import android.content.SharedPreferences
import android.util.Log

/**
 * Created by harsha on 30/1/17.
 *
 * This is a Service that shows the notification about the current charging state
 * and supplies the context to the BatteryReceiver it is registering.
 *
 * This version is modified to reliably handle power events without external receivers.
 */
class ForegroundService : Service() {
    private lateinit var prefs: SharedPreferences
    private lateinit var settings: SharedPreferences
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var mNotifyBuilder: NotificationCompat.Builder
    private var autoResetActive = false
    private var batteryReceiver: BatteryReceiver? = null
    private var notifyID: Int = 1337

    override fun onCreate() {
        super.onCreate()
        prefs = Utils.getPrefs(this)
        settings = Utils.getSettings(this)
        notificationManager = NotificationManagerCompat.from(this)

        val channel = NotificationChannelCompat.Builder(
            Constants.FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName(getString(R.string.notification_channel_name))
            .setDescription(getString(R.string.notification_channel_desc))
            .setLightsEnabled(true)
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(channel)

        mNotifyBuilder = NotificationCompat.Builder(this, Constants.FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // We have to check if we can post notifications first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Permission not granted, stop the service immediately.
            Log.d("ForegroundService", "Notification permission not granted, stopping service.")
            Utils.stopService(this)
            return START_NOT_STICKY
        }

        isRunning = true
        settings.edit().putBoolean(NOTIFICATION_LIVE, true).apply()

        // register a dynamically created receiver to handle battery events
        if (batteryReceiver == null) {
            batteryReceiver = BatteryReceiver(this)
        }
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, filter)
        Log.d("ForegroundService", "BatteryReceiver registered inside service.")

        // Check current charging state on start and update UI
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null && Utils.isCharging(batteryIntent)) {
            // If charging, handle as a charging event immediately
            batteryReceiver?.onReceive(this, batteryIntent)
            Log.d("ForegroundService", "Device is charging on service start, handling charging event.")
        } else {
            // If not charging, show a "waiting" notification
            setNotificationIcon(NOTIF_MAINTAIN)
            setNotificationTitle(getString(R.string.notification_wait_title))
            setNotificationContentText(getString(R.string.notification_wait_content))
            setNotificationActionText(getString(R.string.disable_temporarily)) // Keep this action for manual disable
            updateNotification()
            Log.d("ForegroundService", "Device not charging on service start, waiting for power connection.")
        }

        startForeground(notifyID, mNotifyBuilder.build())
        
        return START_STICKY
    }

    fun setNotificationIcon(iconName: String) {
        val resId = resources.getIdentifier(iconName, "drawable", packageName)
        mNotifyBuilder.setSmallIcon(resId)
    }

    fun setNotificationTitle(text: String) {
        mNotifyBuilder.setContentTitle(text)
    }

    fun setNotificationContentText(text: String) {
        mNotifyBuilder.setContentText(text)
    }

    fun setNotificationActionText(text: String) {
        val disableIntent = Intent(this, ControlBatteryChargeReceiver::class.java).setAction(INTENT_DISABLE_ACTION)
        val disablePendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val action = NotificationCompat.Action.Builder(0, text, disablePendingIntent).build()
        mNotifyBuilder.clearActions()
        mNotifyBuilder.addAction(action)
    }

    fun updateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Permission not granted, do not post.
            return
        }
        notificationManager.notify(notifyID, mNotifyBuilder.build())
    }

    fun setNotificationSound() {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        mNotifyBuilder.setSound(soundUri)
    }

    fun removeNotificationSound() {
        mNotifyBuilder.setSound(null)
    }

    override fun onDestroy() {
        if (autoResetActive && !ignoreAutoReset && prefs.getBoolean(PrefsFragment.KEY_AUTO_RESET_STATS, false)) {
            Utils.resetBatteryStats(this)
        }
        ignoreAutoReset = false

        settings.edit().putBoolean(NOTIFICATION_LIVE, false).apply()
        // unregister the battery event receiver
        if (batteryReceiver != null) {
            unregisterReceiver(batteryReceiver!!)
            // make the BatteryReceiver and dependencies ready for garbage-collection
            batteryReceiver!!.detach(this)
            // clear the reference to the battery receiver for GC
            batteryReceiver = null
        }

        isRunning = false
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    companion object {
        /**
         * Returns whether the service is running right now
         *
         * @return Whether service is running
         */
        var isRunning = false
        private var ignoreAutoReset = false

        /**
         * Ignore the automatic reset when service is shut down the next time
         */
        internal fun ignoreAutoReset() {
            ignoreAutoReset = true
        }
    }
}
