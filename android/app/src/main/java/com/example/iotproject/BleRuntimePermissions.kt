package com.example.iotproject

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object BleRuntimePermissions {
    fun requiredPermissions(): Array<String> {
        return (
            BleCheckpointScanner.requiredPermissions() +
                BlePhoneBeaconAdvertiser.requiredPermissions()
            )
            .distinct()
            .toTypedArray()
    }

    fun hasRequiredPermissions(context: Context): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
