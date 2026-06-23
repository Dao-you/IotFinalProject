package com.example.iotproject

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.iotproject.ui.theme.IoTProjectTheme

class MainActivity : ComponentActivity() {
    private lateinit var bleScanner: BleCheckpointScanner
    private var scanWhenResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val beaconSignals = mutableStateMapOf<String, BeaconSignal>()
        var scanStatus by mutableStateOf("正在準備藍牙掃描")

        bleScanner = BleCheckpointScanner(
            context = applicationContext,
            onSignal = { signal ->
                runOnUiThread {
                    beaconSignals[signal.dataHex] = signal
                    scanStatus = "偵測到 ${signal.dataHex} (${signal.rssi} dBm)"
                }
            },
            onStatus = { status ->
                runOnUiThread {
                    scanStatus = status
                }
            },
        )

        setContent {
            val context = LocalContext.current
            var hasBlePermission by remember {
                mutableStateOf(BleCheckpointScanner.hasRequiredPermissions(context))
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                hasBlePermission = BleCheckpointScanner.hasRequiredPermissions(context)
            }

            DisposableEffect(hasBlePermission) {
                scanWhenResumed = hasBlePermission
                if (hasBlePermission) {
                    bleScanner.start()
                } else {
                    bleScanner.stop()
                }

                onDispose {
                    scanWhenResumed = false
                    bleScanner.stop()
                }
            }

            IoTProjectTheme {
                IotOrienteeringApp(
                    beaconSignals = beaconSignals,
                    scanStatus = scanStatus,
                    hasBlePermission = hasBlePermission,
                    onRequestBlePermission = {
                        permissionLauncher.launch(BleCheckpointScanner.requiredPermissions())
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (scanWhenResumed && ::bleScanner.isInitialized) {
            bleScanner.start()
        }
    }

    override fun onPause() {
        if (::bleScanner.isInitialized) {
            bleScanner.stop()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::bleScanner.isInitialized) {
            bleScanner.stop()
        }
        super.onDestroy()
    }
}

