package com.example.orderingservice

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import kotlinx.coroutines.*

class BatteryMonitorService : Service() {
    private lateinit var robot: Robot
    private val monitoringScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val binder = BatteryBinder()

    // Callback interface for battery updates
    interface BatteryUpdateListener {
        fun onBatteryUpdate(level: Int, isCharging: Boolean)
    }

    private var batteryListener: BatteryUpdateListener? = null

    inner class BatteryBinder : Binder() {
        fun getService(): BatteryMonitorService = this@BatteryMonitorService
    }

    override fun onCreate() {
        super.onCreate()
        robot = Robot.getInstance()
        startBatteryMonitoring()
    }

    private fun startBatteryMonitoring() {
        monitoringScope.launch {
            while (true) {
                val batteryData = robot.batteryData
                val batteryLevel = batteryData?.level ?: 0
                val isCharging = batteryData?.isCharging ?: false

                // Notify listener about battery update
                batteryListener?.onBatteryUpdate(batteryLevel, isCharging)

                if (batteryLevel < 15) {
                    val ttsRequest = TtsRequest.create("My battery is low. I need to recharge.", false)
                    robot.speak(ttsRequest)
                    robot.goTo("home base")
                    break
                }

                delay(30000) // Check every 30 seconds for UI updates
            }
        }
    }

    fun setBatteryUpdateListener(listener: BatteryUpdateListener) {
        this.batteryListener = listener
    }

    fun getCurrentBatteryLevel(): Int {
        return robot.batteryData?.level ?: 0
    }

    fun isCharging(): Boolean {
        return robot.batteryData?.isCharging ?: false
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        monitoringScope.cancel()
        batteryListener = null
    }
}