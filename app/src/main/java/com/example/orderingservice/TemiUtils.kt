package com.example.orderingservice

import android.content.Context
import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest

object TemiUtils {

    fun speakAndLog(message: String, robot: Robot) {
        Log.d("TemiApp", "Speaking: $message")
        robot.speak(TtsRequest.create(message, false))
    }

    fun getBatteryColor(battery: Int, context: Context): Int {
        return when {
            battery <= 15 -> context.getColor(android.R.color.holo_red_dark)
            battery <= 30 -> context.getColor(android.R.color.holo_orange_dark)
            else -> context.getColor(android.R.color.holo_green_dark)
        }
    }

    fun formatSessionId(sessionId: String): String {
        return sessionId.takeLast(8).uppercase()
    }

    fun isValidTable(tableName: String): Boolean {
        return tableName.matches(Regex("^[Tt]able\\s*\\d+$"))
    }
}
