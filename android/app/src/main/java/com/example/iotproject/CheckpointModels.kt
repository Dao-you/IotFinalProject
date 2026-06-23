package com.example.iotproject

import android.os.SystemClock
import java.util.Locale

const val DEFAULT_MANUFACTURER_ID = 0xFFFF
const val DEFAULT_BEACON_DATA_HEX = "00110044"

data class MapPoint(
    val x: Float,
    val y: Float,
) {
    fun clamped(): MapPoint = MapPoint(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
    )
}

data class Checkpoint(
    val id: String,
    val name: String,
    val position: MapPoint,
    val beaconDataHex: String,
)

data class BeaconSignal(
    val manufacturerId: Int,
    val dataHex: String,
    val rssi: Int,
    val seenAtElapsedRealtime: Long = SystemClock.elapsedRealtime(),
    val deviceName: String? = null,
)

fun normalizeBeaconHex(input: String): String = input
    .filter { char ->
        char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
    }
    .uppercase(Locale.US)

fun proximityStrength(signal: BeaconSignal?, nowElapsedRealtime: Long): Float {
    if (signal == null) {
        return 0f
    }

    val ageMillis = nowElapsedRealtime - signal.seenAtElapsedRealtime
    if (ageMillis > 8_000L) {
        return 0f
    }

    val rawStrength = (signal.rssi + 95f) / 50f
    val freshness = 1f - (ageMillis / 8_000f).coerceIn(0f, 1f)
    return (rawStrength.coerceIn(0f, 1f) * freshness).coerceIn(0f, 1f)
}
