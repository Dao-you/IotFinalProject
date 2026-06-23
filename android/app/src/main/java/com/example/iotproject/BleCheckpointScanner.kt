package com.example.iotproject

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BleCheckpointScanner(
    private val context: Context,
    private val onSignal: (BeaconSignal) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (scanCallback != null) {
            return
        }

        if (!hasRequiredPermissions(context)) {
            onStatus("需要藍牙掃描權限")
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

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onStatus("無法啟動 BLE 掃描器")
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emitSignal(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::emitSignal)
            }

            override fun onScanFailed(errorCode: Int) {
                onStatus("BLE 掃描失敗：$errorCode")
                scanCallback = null
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, callback)
            scanCallback = callback
            onStatus("正在掃描檢核點 beacon")
        } catch (error: SecurityException) {
            scanCallback = null
            onStatus("缺少藍牙掃描權限")
        } catch (error: IllegalStateException) {
            scanCallback = null
            onStatus("BLE 掃描器尚未準備好")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val callback = scanCallback ?: return
        scanCallback = null

        try {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: SecurityException) {
            onStatus("缺少藍牙掃描權限")
        } catch (_: IllegalStateException) {
            onStatus("BLE 掃描器已停止")
        }
    }

    private fun emitSignal(result: ScanResult) {
        val scanRecord = result.scanRecord ?: return
        val manufacturerData =
            scanRecord.getManufacturerSpecificData(DEFAULT_MANUFACTURER_ID) ?: return
        val dataHex = manufacturerData.toHexString()
        val deviceName = scanRecord.deviceName ?: safelyReadDeviceName(result)

        onSignal(
            BeaconSignal(
                manufacturerId = DEFAULT_MANUFACTURER_ID,
                dataHex = dataHex,
                rssi = result.rssi,
                deviceName = deviceName,
            ),
        )
    }

    private fun safelyReadDeviceName(result: ScanResult): String? = try {
        result.device?.name
    } catch (_: SecurityException) {
        null
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02X".format(byte)
    }

    companion object {
        fun requiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
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

