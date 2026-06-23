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
    private lateinit var phoneBeaconAdvertiser: BlePhoneBeaconAdvertiser
    private var bleWhenResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val beaconSignals = mutableStateMapOf<String, BeaconSignal>()
        var scanStatus by mutableStateOf("正在準備藍牙掃描")
        var advertiseStatus by mutableStateOf("正在準備手機 beacon")

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
        phoneBeaconAdvertiser = BlePhoneBeaconAdvertiser(
            context = applicationContext,
            onStatus = { status ->
                runOnUiThread {
                    advertiseStatus = status
                }
            },
        )

        setContent {
            val context = LocalContext.current
            var hasBlePermission by remember {
                mutableStateOf(BleRuntimePermissions.hasRequiredPermissions(context))
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                hasBlePermission = BleRuntimePermissions.hasRequiredPermissions(context)
            }

            DisposableEffect(hasBlePermission) {
                bleWhenResumed = hasBlePermission
                if (hasBlePermission) {
                    bleScanner.start()
                    phoneBeaconAdvertiser.start()
                } else {
                    bleScanner.stop()
                    phoneBeaconAdvertiser.stop()
                }

                onDispose {
                    bleWhenResumed = false
                    bleScanner.stop()
                    phoneBeaconAdvertiser.stop()
                }
            }

            IoTProjectTheme {
                IotOrienteeringApp(
                    beaconSignals = beaconSignals,
                    scanStatus = scanStatus,
                    advertiseStatus = advertiseStatus,
                    hasBlePermission = hasBlePermission,
                    onRequestBlePermission = {
                        permissionLauncher.launch(BleRuntimePermissions.requiredPermissions())
                    },
                    onPhoneBeaconDataChange = { beaconDataHex ->
                        phoneBeaconAdvertiser.setBeaconDataHex(beaconDataHex)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (bleWhenResumed && ::bleScanner.isInitialized && ::phoneBeaconAdvertiser.isInitialized) {
            bleScanner.start()
            phoneBeaconAdvertiser.start()
        }
    }

    override fun onPause() {
        if (::bleScanner.isInitialized) {
            bleScanner.stop()
        }
        if (::phoneBeaconAdvertiser.isInitialized) {
            phoneBeaconAdvertiser.stop()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::bleScanner.isInitialized) {
            bleScanner.stop()
        }
        if (::phoneBeaconAdvertiser.isInitialized) {
            phoneBeaconAdvertiser.stop()
        }
        super.onDestroy()
    }
}
