package com.example.iotproject

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BlePhoneBeaconAdvertiser(
    private val context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    private var advertiseCallback: AdvertiseCallback? = null
    private var shouldAdvertise = false
    private var currentDataHex: String? = null
    private var currentPayload: ByteArray? = null
    private var advertisingDataHex: String? = null

    @SuppressLint("MissingPermission")
    fun start() {
        shouldAdvertise = true
        restartAdvertisingIfNeeded()
    }

    fun setBeaconDataHex(beaconDataHex: String?) {
        val cleanDataHex = beaconDataHex?.let(::normalizeBeaconHex).orEmpty()
        val payload = cleanDataHex.toBeaconPayload()

        if (cleanDataHex.isBlank()) {
            currentDataHex = null
            currentPayload = null
            stopCurrentAdvertising(notifyStopped = false)
            onStatus("尚未簽到，不發射手機 beacon")
            return
        }

        if (payload == null) {
            currentDataHex = null
            currentPayload = null
            stopCurrentAdvertising(notifyStopped = false)
            onStatus("手機 beacon data 格式錯誤")
            return
        }

        if (currentDataHex == cleanDataHex) {
            if (shouldAdvertise) {
                restartAdvertisingIfNeeded()
            }
            return
        }

        currentDataHex = cleanDataHex
        currentPayload = payload

        if (shouldAdvertise) {
            restartAdvertisingIfNeeded()
        } else {
            onStatus("手機 beacon 已準備 $cleanDataHex")
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartAdvertisingIfNeeded() {
        val dataHex = currentDataHex
        val payload = currentPayload
        if (!shouldAdvertise) {
            return
        }

        if (dataHex == null || payload == null) {
            stopCurrentAdvertising(notifyStopped = false)
            onStatus("尚未簽到，不發射手機 beacon")
            return
        }

        if (advertiseCallback != null && advertisingDataHex == dataHex) {
            return
        }

        if (!hasRequiredPermissions(context)) {
            onStatus("需要手機 beacon 發射權限")
            return
        }

        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            onStatus("此裝置不支援藍牙")
            return
        }

        if (!adapter.isEnabled) {
            onStatus("藍牙尚未開啟")
            return
        }

        if (!adapter.isMultipleAdvertisementSupported) {
            onStatus("此裝置不支援 BLE beacon 發射")
            return
        }

        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            onStatus("無法啟動手機 beacon 發射器")
            return
        }

        stopCurrentAdvertising(notifyStopped = false)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addManufacturerData(DEFAULT_MANUFACTURER_ID, payload.copyOf())
            .setIncludeDeviceName(false)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                onStatus("正在發射手機 beacon $dataHex")
            }

            override fun onStartFailure(errorCode: Int) {
                advertiseCallback = null
                advertisingDataHex = null
                onStatus("手機 beacon 發射失敗：${advertiseErrorMessage(errorCode)}")
            }
        }

        try {
            advertiseCallback = callback
            advertisingDataHex = dataHex
            advertiser.startAdvertising(settings, data, callback)
            onStatus("正在啟動手機 beacon $dataHex")
        } catch (error: SecurityException) {
            advertiseCallback = null
            advertisingDataHex = null
            onStatus("缺少手機 beacon 發射權限")
        } catch (error: IllegalStateException) {
            advertiseCallback = null
            advertisingDataHex = null
            onStatus("手機 beacon 發射器尚未準備好")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        shouldAdvertise = false
        stopCurrentAdvertising(notifyStopped = true)
    }

    @SuppressLint("MissingPermission")
    private fun stopCurrentAdvertising(notifyStopped: Boolean) {
        val callback = advertiseCallback ?: return
        advertiseCallback = null
        advertisingDataHex = null

        try {
            bluetoothManager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
            if (notifyStopped) {
                onStatus("手機 beacon 已停止")
            }
        } catch (_: SecurityException) {
            onStatus("缺少手機 beacon 發射權限")
        } catch (_: IllegalStateException) {
            if (notifyStopped) {
                onStatus("手機 beacon 已停止")
            }
        }
    }

    private fun advertiseErrorMessage(errorCode: Int): String = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "已經在發射"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "資料太大"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "裝置不支援"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "系統內部錯誤"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "發射器數量已滿"
        else -> errorCode.toString()
    }

    private fun String.toBeaconPayload(): ByteArray? {
        if (isBlank() || length % 2 != 0) {
            return null
        }

        return runCatching {
            ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    companion object {
        fun requiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                emptyArray()
            }
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
}
