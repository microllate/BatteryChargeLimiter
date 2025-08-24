package io.github.muntashirakon.bcl

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.google.android.material.internal.ViewUtils.doOnApplyWindowInsets
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.topjohnwu.superuser.Shell
import io.github.muntashirakon.bcl.Constants.CHARGE_LIMIT_ENABLED
import io.github.muntashirakon.bcl.Constants.CHARGE_OFF_KEY
import io.github.muntashirakon.bcl.Constants.CHARGE_ON_KEY
import io.github.muntashirakon.bcl.Constants.CHARGING_CHANGE_TOLERANCE_MS
import io.github.muntashirakon.bcl.Constants.DEFAULT_DISABLED
import io.github.muntashirakon.bcl.Constants.DEFAULT_ENABLED
import io.github.muntashirakon.bcl.Constants.DEFAULT_FILE
import io.github.muntashirakon.bcl.Constants.LIMIT
import io.github.muntashirakon.bcl.Constants.LIMIT_BY_VOLTAGE
import io.github.muntashirakon.bcl.Constants.MIN
import io.github.muntashirakon.bcl.Constants.MIN_ALLOWED_LIMIT_PC
import io.github.muntashirakon.bcl.Constants.NOTIFICATION_LIVE
import io.github.muntashirakon.bcl.Constants.POWER_CHANGE_TOLERANCE_MS
import io.github.muntashirakon.bcl.Constants.SETTINGS
import io.github.muntashirakon.bcl.receivers.BatteryReceiver
import io.github.muntashirakon.bcl.settings.PrefsFragment
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Created by Michael on 26.03.2017.
 *
 * This class holds utility functions used by all other classes.
 */
object Utils {
    private const val TAG = "Utils"
    private var lastChange: Long = 0
    private var isChangePending = false
    val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun isCharging(intent: Intent?): Boolean {
        if (intent == null) return false
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        return plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB
    }

    @JvmStatic
    fun getBatteryLevel(intent: Intent?): Int {
        if (intent == null) return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level == -1 || scale == -1) -1 else (level * 100 / scale.toFloat()).toInt()
    }

    @JvmStatic
    fun getBatteryInfo(context: Context, intent: Intent, useFahrenheit: Boolean): String {
        val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> context.getString(R.string.health_good)
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> context.getString(R.string.health_overheat)
            BatteryManager.BATTERY_HEALTH_DEAD -> context.getString(R.string.health_dead)
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> context.getString(R.string.health_overvoltage)
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> context.getString(R.string.health_unspecified_failure)
            BatteryManager.BATTERY_HEALTH_COLD -> context.getString(R.string.health_cold)
            else -> context.getString(R.string.health_unknown)
        }
        val status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> context.getString(R.string.status_charging)
            BatteryManager.BATTERY_STATUS_DISCHARGING -> context.getString(R.string.status_discharging)
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> context.getString(R.string.status_not_charging)
            BatteryManager.BATTERY_STATUS_FULL -> context.getString(R.string.status_full)
            else -> context.getString(R.string.status_unknown)
        }
        val technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val tempSuffix = if (useFahrenheit) "°F" else "°C"
        val tempFormatted = if (useFahrenheit) String.format(Locale.ROOT, "%.1f", temp * 9 / 5 + 32) else String.format(Locale.ROOT, "%.1f", temp)
        return String.format(
            "%s, %s, %s, %s%s",
            getBatteryLevel(intent).toString() + "%",
            status,
            health,
            tempFormatted,
            tempSuffix
        )
    }

    fun isChangePending(time: Long): Boolean {
        if (isChangePending) {
            if (System.currentTimeMillis() > lastChange + time) {
                isChangePending = false
            }
        }
        return isChangePending
    }

    @JvmStatic
    fun startServiceIfLimitEnabled(context: Context) {
        val settings = getSettings(context)
        if (settings.getBoolean(CHARGE_LIMIT_ENABLED, false)) {
            val serviceIntent = Intent(context, ForegroundService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    @JvmStatic
    fun stopService(context: Context, ignoreAutoReset: Boolean = true) {
        if (ignoreAutoReset) {
            ForegroundService.ignoreAutoReset()
        }
        val serviceIntent = Intent(context, ForegroundService::class.java)
        context.stopService(serviceIntent)
    }

    @JvmStatic
    fun getPrefs(context: Context): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(context)
    }

    @JvmStatic
    fun getSettings(context: Context): SharedPreferences {
        return context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
    }
    
    // ... 其他Utils函数保持不变 ...

    fun setCtrlFile(context: Context, cf: ControlFile) {
        val settings = getSettings(context)
        settings.edit()
            .putString(Constants.FILE_KEY, cf.file)
            .putString(CHARGE_ON_KEY, cf.chargeOn)
            .putString(CHARGE_OFF_KEY, cf.chargeOff)
            .putBoolean(LIMIT_BY_VOLTAGE, cf.file == getVoltageFile())
            .apply()
    }

    fun getCtrlFile(context: Context): ControlFile {
        val settings = getSettings(context)
        val file = settings.getString(Constants.FILE_KEY, DEFAULT_FILE)
        val chargeOn = settings.getString(CHARGE_ON_KEY, DEFAULT_ENABLED)
        val chargeOff = settings.getString(CHARGE_OFF_KEY, DEFAULT_DISABLED)

        val ctrlFile = ControlFile()
        val fileField = ControlFile::class.java.getDeclaredField("file")
        fileField.isAccessible = true
        fileField.set(ctrlFile, file)

        val chargeOnField = ControlFile::class.java.getDeclaredField("chargeOn")
        chargeOnField.isAccessible = true
        chargeOnField.set(ctrlFile, chargeOn)

        val chargeOffField = ControlFile::class.java.getDeclaredField("chargeOff")
        chargeOffField.isAccessible = true
        chargeOffField.set(ctrlFile, chargeOff)

        return ctrlFile
    }

    @WorkerThread
    fun validateCtrlFiles(context: Context) {
        val gson = Gson()
        val json = context.assets.open("control_files.json").bufferedReader().use { it.readText() }
        val listType = object : TypeToken<List<ControlFile>>() {}.type
        val ctrlFiles = gson.fromJson<List<ControlFile>>(json, listType)
        for (cf in ctrlFiles) {
            cf.validate()
        }
        val voltageFile = getVoltageFile()
        if (voltageFile != null) {
            val cf = ControlFile()
            val fileField = ControlFile::class.java.getDeclaredField("file")
            fileField.isAccessible = true
            fileField.set(cf, voltageFile)
            val chargeOnField = ControlFile::class.java.getDeclaredField("chargeOn")
            chargeOnField.isAccessible = true
            chargeOnField.set(cf, "")
            val chargeOffField = ControlFile::class.java.getDeclaredField("chargeOff")
            chargeOffField.isAccessible = true
            chargeOffField.set(cf, "")
            cf.validate()
        }
    }

    fun getCtrlFiles(context: Context): List<ControlFile> {
        val gson = Gson()
        val json = context.assets.open("control_files.json").bufferedReader().use { it.readText() }
        val listType = object : TypeToken<List<ControlFile>>() {}.type
        val ctrlFiles = gson.fromJson<List<ControlFile>>(json, listType)

        val voltageFile = getVoltageFile()
        if (voltageFile != null) {
            val cf = ControlFile()
            val fileField = ControlFile::class.java.getDeclaredField("file")
            fileField.isAccessible = true
            fileField.set(cf, voltageFile)
            val chargeOnField = ControlFile::class.java.getDeclaredField("chargeOn")
            chargeOnField.isAccessible = true
            chargeOnField.set(cf, "")
            val chargeOffField = ControlFile::class.java.getDeclaredField("chargeOff")
            chargeOffField.isAccessible = true
            chargeOffField.set(cf, "")
            val labelField = ControlFile::class.java.getDeclaredField("label")
            labelField.isAccessible = true
            labelField.set(cf, context.getString(R.string.custom_voltage))
            val detailsField = ControlFile::class.java.getDeclaredField("details")
            detailsField.isAccessible = true
            detailsField.set(cf, context.getString(R.string.custom_voltage_desc))
            val issuesField = ControlFile::class.java.getDeclaredField("issues")
            issuesField.isAccessible = true
            issuesField.set(cf, true)
            ctrlFiles.plus(cf)
        }

        return ctrlFiles
    }

    fun getVoltageFile(): String? {
        val file = File("/sys/class/power_supply/battery/voltage_max")
        return if (file.exists()) file.absolutePath else null
    }

    fun chargeOn(context: Context) {
        val settings = getSettings(context)
        val file = settings.getString(Constants.FILE_KEY, Constants.DEFAULT_FILE)
        val chargeOn = settings.getString(CHARGE_ON_KEY, Constants.DEFAULT_ENABLED)
        Shell.cmd("echo $chargeOn > $file").exec()
    }

    fun chargeOff(context: Context) {
        val settings = getSettings(context)
        val file = settings.getString(Constants.FILE_KEY, Constants.DEFAULT_FILE)
        val chargeOff = settings.getString(CHARGE_OFF_KEY, Constants.DEFAULT_DISABLED)
        Shell.cmd("echo $chargeOff > $file").exec()
    }

    fun changeState(context: Context, state: Int) {
        lastChange = System.currentTimeMillis()
        isChangePending = true
        when (state) {
            CHARGE_ON -> chargeOn(context)
            CHARGE_OFF -> chargeOff(context)
        }
        handler.postDelayed({ isChangePending = false }, 1000)
    }

    fun resetBatteryStats(context: Context) {
        Shell.cmd("dumpsys batterystats --reset").exec()
    }
    
    fun setTheme(context: Context, isMain: Boolean = false) {
        val settings = getSettings(context)
        val theme = settings.getString(PrefsFragment.KEY_THEME, "system")
        when (theme) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        if (isMain) {
            val window = (context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    fun handleLimitChange(context: Context, text: Any?) {
        try {
            val limit = text.toString().toInt()
            val settings = getSettings(context)
            val currentLimit = settings.getInt(LIMIT, Constants.DEFAULT_LIMIT_PC)
            if (limit in MIN_ALLOWED_LIMIT_PC..Constants.MAX_ALLOWED_LIMIT_PC) {
                settings.edit().putInt(LIMIT, limit).apply()
                // Force an update to the service if it's already running
                if (ForegroundService.isRunning) {
                    val serviceIntent = Intent(context, ForegroundService::class.java)
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Toast.makeText(context, R.string.limit_changed, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, R.string.limit_invalid, Toast.LENGTH_LONG).show()
            }
        } catch (e: NumberFormatException) {
            Toast.makeText(context, R.string.limit_invalid, Toast.LENGTH_LONG).show()
        }
    }

    fun setVoltageThreshold(
        context: Context?,
        force: Boolean,
        pContext: Context?,
        threshold: String?
    ) {
        // Not implemented here, but necessary for the file
    }

    @SuppressLint("RestrictedApi")
    @Suppress("DEPRECATION")
    fun applyWindowInsetsAsPaddingNoTop(v: View) {
        doOnApplyWindowInsets(v) { view, insets, initialPadding ->
            if (!ViewCompat.getFitsSystemWindows(view)) {
                // Do not add padding if fitsSystemWindows is false
                return@doOnApplyWindowInsets insets
            }
            val top: Int = initialPadding.top
            val bottom: Int = initialPadding.bottom + insets.systemWindowInsetBottom
            val isRtl = ViewCompat.getLayoutDirection(view) == ViewCompat.LAYOUT_DIRECTION_RTL
            val systemWindowInsetLeft: Int = insets.systemWindowInsetLeft
            val systemWindowInsetRight: Int = insets.systemWindowInsetRight
            var start: Int = initialPadding.start
            var end: Int = initialPadding.end
            if (isRtl) {
                start += systemWindowInsetRight
                end += systemWindowInsetLeft
            } else {
                start += systemWindowInsetLeft
                end += systemWindowInsetRight
            }
            ViewCompat.setPaddingRelative(view, start, top, end, bottom)
            insets
        }
    }

    internal const val CHARGE_ON = 1
    internal const val CHARGE_OFF = 0

    private const val VOLTAGE_FILE = "/sys/class/power_supply/battery/voltage_max"
    private val TAG_VOLTAGE = Utils::class.java.simpleName
    private const val MIN_VOLTAGE = 3700 // mV
    private const val MAX_VOLTAGE = 4400 // mV

    fun getVoltageFile(): String? {
        val file = File(VOLTAGE_FILE)
        return if (file.exists()) file.absolutePath else null
    }

    fun getVoltageThreshold(context: Context): String? {
        val settings = getSettings(context)
        val limitByVoltage = settings.getBoolean(LIMIT_BY_VOLTAGE, false)
        val defaultVoltage = settings.getString(Constants.DEFAULT_VOLTAGE_LIMIT, null)
        val customVoltage = settings.getString(Constants.CUSTOM_VOLTAGE_LIMIT, null)
        return if (limitByVoltage) {
            if (customVoltage != null && isValidVoltageThreshold(customVoltage, defaultVoltage)) {
                customVoltage
            } else {
                defaultVoltage
            }
        } else {
            null
        }
    }

    fun isValidVoltageThreshold(newThreshold: String, currentThreshold: String?): Boolean {
        try {
            val voltage = newThreshold.toInt()
            val minVolThres = Constants.MIN_VOLTAGE_THRESHOLD_MV.toInt()
            val maxVolThres = Constants.MAX_VOLTAGE_THRESHOLD_MV.toInt()
            if (voltage in minVolThres..maxVolThres) {
                return true
            }
        } catch (e: NumberFormatException) {
            Log.e(TAG_VOLTAGE, "Invalid voltage threshold: $newThreshold")
        }
        Log.i(TAG_VOLTAGE, "$newThreshold not valid. Current threshold: $currentThreshold")
        return false
    }
}
