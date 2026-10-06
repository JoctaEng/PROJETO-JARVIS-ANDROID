package com.joctaeng.jarvis.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.joctaeng.jarvis.core.model.DeviceContext
import com.joctaeng.jarvis.core.model.ThermalLevel

/** Lê o estado real do aparelho e o converte para o [DeviceContext] usado pela lógica pura. */
object DeviceState {

    fun snapshot(context: Context, privateMode: Boolean = false): DeviceContext {
        val memory = memoryInfo(context)
        val battery = context.getSystemService(BatteryManager::class.java)
        return DeviceContext(
            totalRamMb = memory.totalMem / MB,
            availableRamMb = memory.availMem / MB,
            lowMemory = memory.lowMemory,
            batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            charging = battery.isCharging,
            thermal = thermalLevel(context),
            online = isOnline(context),
            privateMode = privateMode,
        )
    }

    fun memoryInfo(context: Context): ActivityManager.MemoryInfo =
        ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }

    fun thermalStatus(context: Context): Int = context.getSystemService(PowerManager::class.java).currentThermalStatus

    /** Folga térmica prevista para daqui a 10 s (1.0 = limite de estrangulamento). NaN se indisponível. */
    fun thermalHeadroom(context: Context): Float = context.getSystemService(PowerManager::class.java).getThermalHeadroom(10)

    /** Temperatura da bateria em °C (proxy acessível de aquecimento). */
    fun batteryTemperatureC(context: Context): Float? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    fun batteryPercent(context: Context): Int =
        context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun thermalLevel(context: Context): ThermalLevel = when (thermalStatus(context)) {
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.NORMAL
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.WARM
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.HOT
        else -> ThermalLevel.CRITICAL
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private const val MB = 1024L * 1024L
}
