package io.github.muntashirakon.bcl.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.muntashirakon.bcl.Utils
import android.util.Log

class SimplePowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_POWER_CONNECTED == intent.action) {
            // 这个接收器的唯一作用就是启动服务
            Log.d("SimplePowerReceiver", "ACTION_POWER_CONNECTED received. Starting service.")
            Utils.startServiceIfLimitEnabled(context)
        }
    }
}
