package io.github.muntashirakon.bcl.receivers

import android.content.*
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import io.github.muntashirakon.bcl.Constants.CHARGING_CHANGE_TOLERANCE_MS
import io.github.muntashirakon.bcl.Constants.LIMIT
import io.github.muntashirakon.bcl.Constants.MAX_BACK_OFF_TIME
import io.github.muntashirakon.bcl.Constants.MIN
import io.github.muntashirakon.bcl.Constants.NOTIF_CHARGE
import io.github.muntashirakon.bcl.Constants.NOTIF_MAINTAIN
import io.github.muntashirakon.bcl.Constants.POWER_CHANGE_TOLERANCE_MS
import io.github.muntashirakon.bcl.Constants.SETTINGS
import io.github.muntashirakon.bcl.ForegroundService
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils
import io.github.muntashirakon.bcl.settings.PrefsFragment


/**
 * Created by Michael on 01.04.2017.
 *
 * Dynamically created receiver for battery events. Only registered if power supply is attached.
 */
class BatteryReceiver(private val service: ForegroundService) : BroadcastReceiver() {

    private val tag: String = this::class.java.simpleName
    private var chargedToLimit = false
    private var useFahrenheit = false

    private val preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            Log.d(tag, "Preference changed: $key")
            if (key == PrefsFragment.KEY_TEMP_FAHRENHEIT) {
                useFahrenheit = Utils.getPrefs(service).getBoolean(key, false)
                service.updateNotification()
            }
        }
    private lateinit var prefs: SharedPreferences

    init {
        // Listen for relevant preference changes
        prefs = Utils.getPrefs(service)
        prefs.registerOnSharedPreferenceChangeListener(this.preferenceChangeListener)
        Utils.getSettings(service)
            .registerOnSharedPreferenceChangeListener(this.preferenceChangeListener)

        // set up internal state from preferences
        useFahrenheit = prefs.getBoolean(PrefsFragment.KEY_TEMP_FAHRENHEIT, false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(tag, "Received action: $action")

        val settings = Utils.getSettings(context)
        val chargeLimitEnabled = settings.getBoolean(Constants.CHARGE_LIMIT_ENABLED, false)
        val limitPercentage = settings.getInt(LIMIT, Constants.DEFAULT_LIMIT_PC)
        val minPercentage = settings.getInt(MIN, Constants.MIN_ALLOWED_LIMIT_PC)

        // Reset chargedToLimit when unplugging
        if (action == Intent.ACTION_POWER_DISCONNECTED) {
            chargedToLimit = false
            Log.i(tag, "Power disconnected, resetting chargedToLimit flag.")
            // Stop the service if power is disconnected and limit is not enabled
            if (!chargeLimitEnabled) {
                Log.d(tag, "Power disconnected and limit is disabled, stopping service.")
                Utils.stopService(context, false)
            }
            return
        }

        // Only handle battery changes when charging
        if (action == Intent.ACTION_BATTERY_CHANGED && !Utils.isCharging(intent)) {
            Log.d(tag, "Not charging, ignoring battery change event.")
            return
        }

        // Get battery percentage and check against limits
        val currentPercentage = Utils.getBatteryLevel(intent)

        if (chargedToLimit) {
            Log.i(tag, "Charged to limit already. Checking if below min limit for re-charging.")
            // Already charged to limit, check if we need to recharge
            if (currentPercentage <= minPercentage) {
                // Time to recharge
                Log.i(tag, "Battery level $currentPercentage <= $minPercentage, restarting charge.")
                Utils.chargeOn(context)
                service.setNotificationTitle(service.getString(R.string.charging_to_x, limitPercentage))
                service.setNotificationIcon(NOTIF_CHARGE)
                service.setNotificationActionText(service.getString(R.string.disable_temporarily))
                Utils.changeState(service, Utils.CHARGE_ON)
                chargedToLimit = false // Reset the flag
            }
        } else {
            // Not charged to limit yet, check if we've reached it
            if (currentPercentage >= limitPercentage) {
                Log.i(tag, "Battery level $currentPercentage >= $limitPercentage, stopping charge.")
                Utils.chargeOff(context)
                service.setNotificationIcon(NOTIF_MAINTAIN)
                service.setNotificationTitle(service.getString(R.string.waiting_until_x, minPercentage))
                service.setNotificationActionText(service.getString(R.string.disable_temporarily))
                Utils.changeState(service, Utils.CHARGE_OFF)
                chargedToLimit = true // Set the flag
            } else {
                Log.d(tag, "Charging to limit. Current level $currentPercentage < $limitPercentage.")
                // Update notification to show charging status
                service.setNotificationTitle(service.getString(R.string.charging_to_x, limitPercentage))
                service.setNotificationIcon(NOTIF_CHARGE)
                service.setNotificationActionText(service.getString(R.string.disable_temporarily))
                Utils.changeState(service, Utils.CHARGE_ON)
            }
        }
        
        // update battery status information and rebuild notification
        service.setNotificationContentText(Utils.getBatteryInfo(service, intent, useFahrenheit))
        service.updateNotification()
        service.removeNotificationSound()
    }

    fun detach(context: Context) {
        // unregister the listener that listens for relevant change events
        prefs.unregisterOnSharedPreferenceChangeListener(this.preferenceChangeListener)
        Utils.getSettings(context)
            .unregisterOnSharedPreferenceChangeListener(this.preferenceChangeListener)
        // technically not necessary, but it prevents inlining of this required field
        // see end of https://developer.android.com/guide/topics/ui/settings.html#Listening
        this.preferenceChangeListener = null
    }

    companion object {
        private const val CHARGE_FULL = 0
        private const val CHARGE_STOP = 1
        private const val CHARGE_REFRESH = 2

        private val handler = Handler(Looper.getMainLooper())
        internal var backOffTime = CHARGING_CHANGE_TOLERANCE_MS

        /**
         * Enables the auto-reset functionality.
         */
        fun enableAutoReset() {
            ForegroundService.ignoreAutoReset()
        }
    }
}
