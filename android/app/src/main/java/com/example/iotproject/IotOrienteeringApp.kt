package com.example.iotproject

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.iotproject.ui.theme.IoTProjectTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val AppBackground = Color(0xFFF5F7F7)
private val PanelBackground = Color(0xFFFFFFFF)
private val MapBackground = Color(0xFFEAF0EA)
private val MapGrid = Color(0xFFCDD8CE)
private val MapBorder = Color(0xFF87968E)
private val RouteColor = Color(0xFFE08C2B)
private val PinIdle = Color(0xFF3C596B)
private val PinActive = Color(0xFF13A36F)
private val PinSelected = Color(0xFF2D6CDF)
private val PinCheckedIn = Color(0xFF7652B7)
private val WarningBackground = Color(0xFFFFF3CF)
private val CheckInBackground = Color(0xFFEAF7F1)
private val CheckInAccent = Color(0xFF167653)

private const val CHECK_IN_STRENGTH_THRESHOLD = 0.62f

private data class MapBackgroundImage(
    val uri: Uri? = null,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
)

private data class CheckInRecord(
    val checkpointId: String,
    val checkpointName: String,
    val beaconDataHex: String,
    val checkedInAtMillis: Long,
    val rssi: Int?,
)

private enum class AppScreen(val title: String) {
    Game("遊戲"),
    Admin("管理"),
}

@Composable
fun IotOrienteeringApp(
    beaconSignals: Map<String, BeaconSignal>,
    scanStatus: String,
    hasBlePermission: Boolean,
    onRequestBlePermission: () -> Unit,
) {
    var currentScreen by remember { mutableStateOf(AppScreen.Game) }
    var nowElapsedRealtime by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    val checkpoints = remember {
        mutableStateListOf(
            Checkpoint(
                id = "cp_start",
                name = "入口",
                position = MapPoint(0.22f, 0.34f),
                beaconDataHex = DEFAULT_BEACON_DATA_HEX,
            ),
            Checkpoint(
                id = "cp_court",
                name = "中庭",
                position = MapPoint(0.55f, 0.48f),
                beaconDataHex = DEFAULT_BEACON_DATA_HEX,
            ),
            Checkpoint(
                id = "cp_finish",
                name = "終點",
                position = MapPoint(0.78f, 0.72f),
                beaconDataHex = DEFAULT_BEACON_DATA_HEX,
            ),
        )
    }
    var selectedCheckpointId by remember { mutableStateOf(checkpoints.firstOrNull()?.id) }
    var mapBackgroundImage by remember { mutableStateOf(MapBackgroundImage()) }
    val checkInRecords = remember { mutableStateListOf<CheckInRecord>() }
    var latestCheckInMessage by remember { mutableStateOf<String?>(null) }
    val checkedInCheckpointIds = checkInRecords.map { record -> record.checkpointId }.toSet()

    LaunchedEffect(Unit) {
        while (true) {
            nowElapsedRealtime = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
    }

    fun moveCheckpoint(checkpointId: String, position: MapPoint) {
        val index = checkpoints.indexOfFirst { it.id == checkpointId }
        if (index >= 0) {
            checkpoints[index] = checkpoints[index].copy(position = position.clamped())
        }
    }

    fun addCheckpoint(name: String, beaconDataHex: String) {
        val cleanBeaconData = normalizeBeaconHex(beaconDataHex).ifBlank {
            DEFAULT_BEACON_DATA_HEX
        }
        val checkpointNumber = checkpoints.size + 1
        val checkpoint = Checkpoint(
            id = "cp_${SystemClock.elapsedRealtime()}",
            name = name.trim().ifBlank { "檢核點 $checkpointNumber" },
            position = MapPoint(0.5f, 0.5f),
            beaconDataHex = cleanBeaconData,
        )
        checkpoints.add(checkpoint)
        selectedCheckpointId = checkpoint.id
    }

    fun updateCheckpointBeaconData(checkpointId: String, beaconDataHex: String) {
        val cleanBeaconData = normalizeBeaconHex(beaconDataHex)
        if (cleanBeaconData.isBlank()) {
            return
        }

        val index = checkpoints.indexOfFirst { it.id == checkpointId }
        if (index >= 0) {
            checkpoints[index] = checkpoints[index].copy(beaconDataHex = cleanBeaconData)
        }
    }

    fun setMapBackgroundImage(uri: Uri) {
        mapBackgroundImage = MapBackgroundImage(uri = uri)
    }

    fun moveMapBackgroundImage(deltaX: Float, deltaY: Float) {
        mapBackgroundImage = mapBackgroundImage.copy(
            offsetX = (mapBackgroundImage.offsetX + deltaX).coerceIn(-1f, 1f),
            offsetY = (mapBackgroundImage.offsetY + deltaY).coerceIn(-1f, 1f),
        )
    }

    fun setMapBackgroundScale(scale: Float) {
        mapBackgroundImage = mapBackgroundImage.copy(scale = scale.coerceIn(0.4f, 3f))
    }

    fun resetMapBackgroundPlacement() {
        mapBackgroundImage = mapBackgroundImage.copy(
            offsetX = 0f,
            offsetY = 0f,
            scale = 1f,
        )
    }

    fun checkInCheckpoint(checkpoint: Checkpoint) {
        if (checkInRecords.any { record -> record.checkpointId == checkpoint.id }) {
            return
        }

        val signal = beaconSignals[checkpoint.beaconDataHex]
        checkInRecords.add(
            0,
            CheckInRecord(
                checkpointId = checkpoint.id,
                checkpointName = checkpoint.name,
                beaconDataHex = checkpoint.beaconDataHex,
                checkedInAtMillis = System.currentTimeMillis(),
                rssi = signal?.rssi,
            ),
        )
        latestCheckInMessage = "${checkpoint.name} 簽到成功。可以到互動裝置前拍攝團體照。"
    }

    Scaffold(containerColor = AppBackground) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AppHeader(scanStatus = scanStatus)
            TabRow(selectedTabIndex = currentScreen.ordinal) {
                AppScreen.entries.forEach { screen ->
                    Tab(
                        selected = currentScreen == screen,
                        onClick = { currentScreen = screen },
                        text = { Text(screen.title) },
                    )
                }
            }

            when (currentScreen) {
                AppScreen.Game -> GameScreen(
                    checkpoints = checkpoints,
                    beaconSignals = beaconSignals,
                    nowElapsedRealtime = nowElapsedRealtime,
                    mapBackgroundImage = mapBackgroundImage,
                    checkedInCheckpointIds = checkedInCheckpointIds,
                    checkInRecords = checkInRecords,
                    latestCheckInMessage = latestCheckInMessage,
                    hasBlePermission = hasBlePermission,
                    onRequestBlePermission = onRequestBlePermission,
                    onCheckIn = ::checkInCheckpoint,
                )

                AppScreen.Admin -> AdminScreen(
                    checkpoints = checkpoints,
                    beaconSignals = beaconSignals,
                    nowElapsedRealtime = nowElapsedRealtime,
                    mapBackgroundImage = mapBackgroundImage,
                    selectedCheckpointId = selectedCheckpointId,
                    onSelectCheckpoint = { selectedCheckpointId = it },
                    onMoveCheckpoint = ::moveCheckpoint,
                    onAddCheckpoint = ::addCheckpoint,
                    onUpdateCheckpointBeaconData = ::updateCheckpointBeaconData,
                    onMapBackgroundSelected = ::setMapBackgroundImage,
                    onMoveMapBackground = ::moveMapBackgroundImage,
                    onSetMapBackgroundScale = ::setMapBackgroundScale,
                    onResetMapBackgroundPlacement = ::resetMapBackgroundPlacement,
                )
            }
        }
    }
}

@Composable
private fun AppHeader(scanStatus: String) {
    Surface(
        color = PanelBackground,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "定向越野互動裝置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = scanStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF58666D),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GameScreen(
    checkpoints: List<Checkpoint>,
    beaconSignals: Map<String, BeaconSignal>,
    nowElapsedRealtime: Long,
    mapBackgroundImage: MapBackgroundImage,
    checkedInCheckpointIds: Set<String>,
    checkInRecords: List<CheckInRecord>,
    latestCheckInMessage: String?,
    hasBlePermission: Boolean,
    onRequestBlePermission: () -> Unit,
    onCheckIn: (Checkpoint) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!hasBlePermission) {
            PermissionBanner(onRequestBlePermission = onRequestBlePermission)
        }

        MapSurface(
            checkpoints = checkpoints,
            beaconSignals = beaconSignals,
            nowElapsedRealtime = nowElapsedRealtime,
            mapBackgroundImage = mapBackgroundImage,
            checkedInCheckpointIds = checkedInCheckpointIds,
            selectedCheckpointId = null,
            editable = false,
            adjustingMapBackground = false,
            onSelectCheckpoint = {},
            onMoveCheckpoint = { _, _ -> },
            onMoveMapBackground = { _, _ -> },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
        )

        latestCheckInMessage?.let { message ->
            CheckInSuccessBanner(message = message)
        }

        CheckpointSignalList(
            checkpoints = checkpoints,
            beaconSignals = beaconSignals,
            nowElapsedRealtime = nowElapsedRealtime,
            checkedInCheckpointIds = checkedInCheckpointIds,
            onCheckIn = onCheckIn,
        )

        CheckInRecordList(records = checkInRecords)
    }
}

@Composable
private fun PermissionBanner(onRequestBlePermission: () -> Unit) {
    Surface(
        color = WarningBackground,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "尚未授權 BLE 掃描",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onRequestBlePermission) {
                Text("授權")
            }
        }
    }
}

@Composable
private fun AdminScreen(
    checkpoints: List<Checkpoint>,
    beaconSignals: Map<String, BeaconSignal>,
    nowElapsedRealtime: Long,
    mapBackgroundImage: MapBackgroundImage,
    selectedCheckpointId: String?,
    onSelectCheckpoint: (String) -> Unit,
    onMoveCheckpoint: (String, MapPoint) -> Unit,
    onAddCheckpoint: (String, String) -> Unit,
    onUpdateCheckpointBeaconData: (String, String) -> Unit,
    onMapBackgroundSelected: (Uri) -> Unit,
    onMoveMapBackground: (Float, Float) -> Unit,
    onSetMapBackgroundScale: (Float) -> Unit,
    onResetMapBackgroundPlacement: () -> Unit,
) {
    var newCheckpointName by remember { mutableStateOf("") }
    var newBeaconDataHex by remember { mutableStateOf(DEFAULT_BEACON_DATA_HEX) }
    val selectedCheckpoint = checkpoints.firstOrNull { it.id == selectedCheckpointId }
    var editedBeaconDataHex by remember { mutableStateOf("") }
    var adjustingMapBackground by remember { mutableStateOf(false) }

    LaunchedEffect(selectedCheckpoint?.id, selectedCheckpoint?.beaconDataHex) {
        editedBeaconDataHex = selectedCheckpoint?.beaconDataHex.orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MapSurface(
            checkpoints = checkpoints,
            beaconSignals = beaconSignals,
            nowElapsedRealtime = nowElapsedRealtime,
            mapBackgroundImage = mapBackgroundImage,
            checkedInCheckpointIds = emptySet(),
            selectedCheckpointId = selectedCheckpointId,
            editable = true,
            adjustingMapBackground = adjustingMapBackground,
            onSelectCheckpoint = onSelectCheckpoint,
            onMoveCheckpoint = onMoveCheckpoint,
            onMoveMapBackground = onMoveMapBackground,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
        )

        MapBackgroundPanel(
            mapBackgroundImage = mapBackgroundImage,
            adjustingMapBackground = adjustingMapBackground,
            onMapBackgroundSelected = onMapBackgroundSelected,
            onAdjustingMapBackgroundChange = { adjustingMapBackground = it },
            onSetMapBackgroundScale = onSetMapBackgroundScale,
            onResetMapBackgroundPlacement = onResetMapBackgroundPlacement,
        )

        AddCheckpointPanel(
            newCheckpointName = newCheckpointName,
            newBeaconDataHex = newBeaconDataHex,
            onNameChange = { newCheckpointName = it },
            onBeaconDataChange = { newBeaconDataHex = normalizeBeaconHex(it) },
            onAddCheckpoint = {
                onAddCheckpoint(newCheckpointName, newBeaconDataHex)
                newCheckpointName = ""
            },
        )

        SelectedCheckpointEditor(
            checkpoint = selectedCheckpoint,
            editedBeaconDataHex = editedBeaconDataHex,
            onBeaconDataChange = { editedBeaconDataHex = normalizeBeaconHex(it) },
            onApplyBeaconData = {
                if (selectedCheckpoint != null) {
                    onUpdateCheckpointBeaconData(
                        selectedCheckpoint.id,
                        editedBeaconDataHex,
                    )
                }
            },
        )

        CheckpointAdminList(
            checkpoints = checkpoints,
            selectedCheckpointId = selectedCheckpointId,
            onSelectCheckpoint = onSelectCheckpoint,
        )
    }
}

@Composable
private fun MapBackgroundPanel(
    mapBackgroundImage: MapBackgroundImage,
    adjustingMapBackground: Boolean,
    onMapBackgroundSelected: (Uri) -> Unit,
    onAdjustingMapBackgroundChange: (Boolean) -> Unit,
    onSetMapBackgroundScale: (Float) -> Unit,
    onResetMapBackgroundPlacement: () -> Unit,
) {
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            onMapBackgroundSelected(uri)
            onAdjustingMapBackgroundChange(true)
        }
    }
    val hasBackgroundImage = mapBackgroundImage.uri != null

    Surface(
        color = PanelBackground,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "地圖底圖",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (hasBackgroundImage) "更換圖片" else "匯入圖片")
                }
                OutlinedButton(
                    onClick = {
                        onAdjustingMapBackgroundChange(!adjustingMapBackground)
                    },
                    enabled = hasBackgroundImage,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (adjustingMapBackground) "完成調整" else "調整位置")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        onSetMapBackgroundScale(mapBackgroundImage.scale - 0.1f)
                    },
                    enabled = hasBackgroundImage,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("縮小")
                }
                OutlinedButton(
                    onClick = {
                        onSetMapBackgroundScale(mapBackgroundImage.scale + 0.1f)
                    },
                    enabled = hasBackgroundImage,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("放大")
                }
                OutlinedButton(
                    onClick = onResetMapBackgroundPlacement,
                    enabled = hasBackgroundImage,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("置中")
                }
            }
            Text(
                text = if (adjustingMapBackground) {
                    "拖曳地圖可移動底圖"
                } else {
                    "底圖比例：${(mapBackgroundImage.scale * 100).roundToInt()}%"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF667176),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AddCheckpointPanel(
    newCheckpointName: String,
    newBeaconDataHex: String,
    onNameChange: (String) -> Unit,
    onBeaconDataChange: (String) -> Unit,
    onAddCheckpoint: () -> Unit,
) {
    Surface(
        color = PanelBackground,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "新增檢核點",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = newCheckpointName,
                onValueChange = onNameChange,
                label = { Text("檢核點名稱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newBeaconDataHex,
                onValueChange = onBeaconDataChange,
                label = { Text("Beacon Data") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onAddCheckpoint,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("新增")
            }
        }
    }
}

@Composable
private fun SelectedCheckpointEditor(
    checkpoint: Checkpoint?,
    editedBeaconDataHex: String,
    onBeaconDataChange: (String) -> Unit,
    onApplyBeaconData: () -> Unit,
) {
    Surface(
        color = Color(0xFFF6F8F8),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = checkpoint?.let { "編輯 ${it.name}" } ?: "選取檢核點後可編輯 Beacon Data",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedTextField(
                value = editedBeaconDataHex,
                onValueChange = onBeaconDataChange,
                label = { Text("Beacon Data") },
                singleLine = true,
                enabled = checkpoint != null,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onApplyBeaconData,
                enabled = checkpoint != null &&
                    editedBeaconDataHex.isNotBlank() &&
                    editedBeaconDataHex != checkpoint.beaconDataHex,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("套用")
            }
        }
    }
}

@Composable
private fun CheckpointAdminList(
    checkpoints: List<Checkpoint>,
    selectedCheckpointId: String?,
    onSelectCheckpoint: (String) -> Unit,
) {
    Surface(
        color = PanelBackground,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "檢核點列表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                checkpoints.forEach { checkpoint ->
                    CheckpointAdminRow(
                        checkpoint = checkpoint,
                        isSelected = checkpoint.id == selectedCheckpointId,
                        onClick = { onSelectCheckpoint(checkpoint.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MapSurface(
    checkpoints: List<Checkpoint>,
    beaconSignals: Map<String, BeaconSignal>,
    nowElapsedRealtime: Long,
    mapBackgroundImage: MapBackgroundImage,
    checkedInCheckpointIds: Set<String>,
    selectedCheckpointId: String?,
    editable: Boolean,
    adjustingMapBackground: Boolean,
    onSelectCheckpoint: (String) -> Unit,
    onMoveCheckpoint: (String, MapPoint) -> Unit,
    onMoveMapBackground: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    val backgroundBitmap = remember(context, mapBackgroundImage.uri) {
        mapBackgroundImage.uri?.let { uri -> loadImageBitmap(context, uri) }
    }
    val checkpointEditingEnabled = editable && !adjustingMapBackground
    val density = LocalDensity.current
    val markerSize = if (editable) 72.dp else 78.dp
    val markerSizePx = with(density) { markerSize.toPx() }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MapBackground)
            .border(1.dp, MapBorder, RoundedCornerShape(8.dp))
            .onSizeChanged { mapSize = it }
            .then(
                if (editable && adjustingMapBackground && backgroundBitmap != null) {
                    Modifier.pointerInput(mapSize) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (mapSize.width > 0 && mapSize.height > 0) {
                                onMoveMapBackground(
                                    dragAmount.x / mapSize.width,
                                    dragAmount.y / mapSize.height,
                                )
                            }
                        }
                    }
                } else {
                    Modifier.pointerInput(checkpointEditingEnabled, selectedCheckpointId, mapSize) {
                        if (checkpointEditingEnabled) {
                            detectTapGestures { tapOffset ->
                                val checkpointId = selectedCheckpointId ?: return@detectTapGestures
                                if (mapSize.width > 0 && mapSize.height > 0) {
                                    onMoveCheckpoint(
                                        checkpointId,
                                        MapPoint(
                                            x = tapOffset.x / mapSize.width,
                                            y = tapOffset.y / mapSize.height,
                                        ).clamped(),
                                    )
                                }
                            }
                        }
                    }
                },
            ),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val gridStroke = 1.dp.toPx()
            val routeStroke = 3.dp.toPx()

            if (backgroundBitmap != null) {
                val imageAspect = backgroundBitmap.width.toFloat() / backgroundBitmap.height
                val baseWidth: Float
                val baseHeight: Float

                if (imageAspect >= 1f) {
                    baseWidth = size.width
                    baseHeight = size.width / imageAspect
                } else {
                    baseHeight = size.height
                    baseWidth = size.height * imageAspect
                }

                val drawWidth = (baseWidth * mapBackgroundImage.scale).coerceAtLeast(1f)
                val drawHeight = (baseHeight * mapBackgroundImage.scale).coerceAtLeast(1f)
                val left = (size.width - drawWidth) / 2f +
                    mapBackgroundImage.offsetX * size.width
                val top = (size.height - drawHeight) / 2f +
                    mapBackgroundImage.offsetY * size.height

                drawImage(
                    image = backgroundBitmap,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
                    alpha = 0.88f,
                    filterQuality = FilterQuality.Medium,
                )
            }

            for (index in 1..4) {
                val x = size.width * index / 5f
                val y = size.height * index / 5f
                drawLine(
                    color = MapGrid,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = gridStroke,
                )
                drawLine(
                    color = MapGrid,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = gridStroke,
                )
            }

            checkpoints.zipWithNext().forEach { (from, to) ->
                drawLine(
                    color = RouteColor.copy(alpha = 0.62f),
                    start = Offset(
                        x = from.position.x * size.width,
                        y = from.position.y * size.height,
                    ),
                    end = Offset(
                        x = to.position.x * size.width,
                        y = to.position.y * size.height,
                    ),
                    strokeWidth = routeStroke,
                )
            }
        }

        checkpoints.forEach { checkpoint ->
            val signal = beaconSignals[checkpoint.beaconDataHex]
            val strength = proximityStrength(signal, nowElapsedRealtime)
            val checkedIn = checkpoint.id in checkedInCheckpointIds
            val left = checkpoint.position.x * mapSize.width - markerSizePx / 2f
            val top = checkpoint.position.y * mapSize.height - markerSizePx / 2f
            var dragPosition = checkpoint.position
            val interactionSource = remember(checkpoint.id) { MutableInteractionSource() }

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = left.roundToInt(),
                            y = top.roundToInt(),
                        )
                    }
                    .size(markerSize)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = checkpointEditingEnabled,
                        onClick = { onSelectCheckpoint(checkpoint.id) },
                    )
                    .then(
                        if (checkpointEditingEnabled) {
                            Modifier.pointerInput(checkpoint.id, mapSize) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragPosition = checkpoint.position
                                        onSelectCheckpoint(checkpoint.id)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (mapSize.width > 0 && mapSize.height > 0) {
                                            dragPosition = MapPoint(
                                                x = dragPosition.x + dragAmount.x / mapSize.width,
                                                y = dragPosition.y + dragAmount.y / mapSize.height,
                                            ).clamped()
                                            onMoveCheckpoint(checkpoint.id, dragPosition)
                                        }
                                    },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                CheckpointMarker(
                    checkpoint = checkpoint,
                    strength = strength,
                    selected = checkpoint.id == selectedCheckpointId,
                    checkedIn = checkedIn,
                )
            }
        }
    }
}

@Composable
private fun CheckpointMarker(
    checkpoint: Checkpoint,
    strength: Float,
    selected: Boolean,
    checkedIn: Boolean,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(
                x = size.width / 2f,
                y = size.height / 2f - 8.dp.toPx(),
            )
            val effectRadius = 19.dp.toPx() + 22.dp.toPx() * strength
            val pinRadius = 9.dp.toPx()
            val proximityColor = if (checkedIn) PinCheckedIn else PinActive
            val pinColor = when {
                checkedIn -> PinCheckedIn
                strength > 0f -> PinActive
                else -> PinIdle
            }

            if (strength > 0f) {
                drawCircle(
                    color = proximityColor.copy(alpha = 0.16f + 0.28f * strength),
                    radius = effectRadius,
                    center = center,
                )
                drawCircle(
                    color = proximityColor.copy(alpha = 0.55f),
                    radius = effectRadius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx() + 4.dp.toPx() * strength),
                )
            }

            if (checkedIn) {
                drawCircle(
                    color = PinCheckedIn.copy(alpha = 0.22f),
                    radius = 15.dp.toPx(),
                    center = center,
                )
            }

            if (selected) {
                drawCircle(
                    color = PinSelected,
                    radius = 18.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            drawCircle(
                color = pinColor,
                radius = pinRadius,
                center = center,
            )
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = center,
            )
        }

        Surface(
            color = if (checkedIn) PinCheckedIn.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.92f),
            shape = RoundedCornerShape(6.dp),
            shadowElevation = 1.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 70.dp),
        ) {
            Text(
                text = checkpoint.name,
                style = MaterialTheme.typography.labelSmall,
                color = if (checkedIn) Color.White else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun CheckInSuccessBanner(message: String) {
    Surface(
        color = CheckInBackground,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "簽到成功",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = CheckInAccent,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF40534C),
            )
        }
    }
}

@Composable
private fun CheckpointSignalList(
    checkpoints: List<Checkpoint>,
    beaconSignals: Map<String, BeaconSignal>,
    nowElapsedRealtime: Long,
    checkedInCheckpointIds: Set<String>,
    onCheckIn: (Checkpoint) -> Unit,
) {
    Surface(
        color = PanelBackground,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "檢核點狀態",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            checkpoints.forEach { checkpoint ->
                val signal = beaconSignals[checkpoint.beaconDataHex]
                val strength = proximityStrength(signal, nowElapsedRealtime)
                val checkedIn = checkpoint.id in checkedInCheckpointIds
                CheckpointSignalRow(
                    checkpoint = checkpoint,
                    signal = signal,
                    strength = strength,
                    checkedIn = checkedIn,
                    canCheckIn = !checkedIn && strength >= CHECK_IN_STRENGTH_THRESHOLD,
                    onCheckIn = { onCheckIn(checkpoint) },
                )
            }
        }
    }
}

@Composable
private fun CheckpointSignalRow(
    checkpoint: Checkpoint,
    signal: BeaconSignal?,
    strength: Float,
    checkedIn: Boolean,
    canCheckIn: Boolean,
    onCheckIn: () -> Unit,
) {
    val rowBackground = if (checkedIn) CheckInBackground else Color(0xFFF6F8F8)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(rowBackground)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = checkpoint.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = signal?.let { "${it.dataHex} / ${it.rssi} dBm" } ?: checkpoint.beaconDataHex,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF667176),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CheckInStatusBadge(
                checkedIn = checkedIn,
                canCheckIn = canCheckIn,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when {
                    checkedIn -> "已完成簽到，可前往互動裝置拍攝團體照"
                    canCheckIn -> "已靠近檢核點，可以簽到"
                    else -> "靠近檢核點後可簽到"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (checkedIn) CheckInAccent else Color(0xFF667176),
                modifier = Modifier.weight(1f),
            )
            SignalMeter(strength = strength)
            if (canCheckIn) {
                Button(onClick = onCheckIn) {
                    Text("簽到")
                }
            }
        }
    }
}

@Composable
private fun CheckInStatusBadge(
    checkedIn: Boolean,
    canCheckIn: Boolean,
) {
    val text = when {
        checkedIn -> "已簽到"
        canCheckIn -> "可簽到"
        else -> "未簽到"
    }
    val background = when {
        checkedIn -> PinCheckedIn.copy(alpha = 0.14f)
        canCheckIn -> PinActive.copy(alpha = 0.14f)
        else -> Color(0xFFE1E7E5)
    }
    val foreground = when {
        checkedIn -> PinCheckedIn
        canCheckIn -> PinActive
        else -> Color(0xFF667176)
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun CheckInRecordList(records: List<CheckInRecord>) {
    Surface(
        color = PanelBackground,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "簽到記錄",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (records.isEmpty()) {
                Text(
                    text = "尚未完成任何檢核點簽到",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF667176),
                )
            } else {
                records.forEach { record ->
                    CheckInRecordRow(record = record)
                }
            }
        }
    }
}

@Composable
private fun CheckInRecordRow(record: CheckInRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF6F8F8))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(PinCheckedIn),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.checkpointName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = record.rssi?.let { "${record.beaconDataHex} / $it dBm" }
                    ?: record.beaconDataHex,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF667176),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatCheckInTime(record.checkedInAtMillis),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF667176),
        )
    }
}

@Composable
private fun SignalMeter(strength: Float) {
    Box(
        modifier = Modifier
            .width(92.dp)
            .height(8.dp)
            .clip(CircleShape)
            .background(Color(0xFFDDE4E1)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(strength.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(if (strength > 0.62f) PinActive else RouteColor),
        )
    }
}

@Composable
private fun CheckpointAdminRow(
    checkpoint: Checkpoint,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (isSelected) Color(0xFFE8F0FF) else Color(0xFFF6F8F8)
    Surface(
        color = background,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) PinSelected else PinIdle),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = checkpoint.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = checkpoint.beaconDataHex,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF667176),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${(checkpoint.position.x * 100).roundToInt()}%, ${(checkpoint.position.y * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF667176),
            )
        }
    }
}

private fun loadImageBitmap(context: android.content.Context, uri: Uri): ImageBitmap? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream)?.asImageBitmap()
        }
    }.getOrNull()
}

private fun formatCheckInTime(timeMillis: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(Date(timeMillis))
}

@Preview(showBackground = true)
@Composable
private fun IotOrienteeringAppPreview() {
    IoTProjectTheme {
        IotOrienteeringApp(
            beaconSignals = emptyMap(),
            scanStatus = "正在掃描檢核點 beacon",
            hasBlePermission = true,
            onRequestBlePermission = {},
        )
    }
}
