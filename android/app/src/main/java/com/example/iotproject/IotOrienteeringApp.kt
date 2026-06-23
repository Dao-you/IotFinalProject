package com.example.iotproject

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.iotproject.ui.theme.IoTProjectTheme
import kotlinx.coroutines.delay
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
private val WarningBackground = Color(0xFFFFF3CF)

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
                    hasBlePermission = hasBlePermission,
                    onRequestBlePermission = onRequestBlePermission,
                )

                AppScreen.Admin -> AdminScreen(
                    checkpoints = checkpoints,
                    beaconSignals = beaconSignals,
                    nowElapsedRealtime = nowElapsedRealtime,
                    selectedCheckpointId = selectedCheckpointId,
                    onSelectCheckpoint = { selectedCheckpointId = it },
                    onMoveCheckpoint = ::moveCheckpoint,
                    onAddCheckpoint = ::addCheckpoint,
                    onUpdateCheckpointBeaconData = ::updateCheckpointBeaconData,
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
    hasBlePermission: Boolean,
    onRequestBlePermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!hasBlePermission) {
            PermissionBanner(onRequestBlePermission = onRequestBlePermission)
        }

        MapSurface(
            checkpoints = checkpoints,
            beaconSignals = beaconSignals,
            nowElapsedRealtime = nowElapsedRealtime,
            selectedCheckpointId = null,
            editable = false,
            onSelectCheckpoint = {},
            onMoveCheckpoint = { _, _ -> },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        CheckpointSignalList(
            checkpoints = checkpoints,
            beaconSignals = beaconSignals,
            nowElapsedRealtime = nowElapsedRealtime,
        )
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
    selectedCheckpointId: String?,
    onSelectCheckpoint: (String) -> Unit,
    onMoveCheckpoint: (String, MapPoint) -> Unit,
    onAddCheckpoint: (String, String) -> Unit,
    onUpdateCheckpointBeaconData: (String, String) -> Unit,
) {
    var newCheckpointName by remember { mutableStateOf("") }
    var newBeaconDataHex by remember { mutableStateOf(DEFAULT_BEACON_DATA_HEX) }
    val selectedCheckpoint = checkpoints.firstOrNull { it.id == selectedCheckpointId }
    var editedBeaconDataHex by remember { mutableStateOf("") }

    LaunchedEffect(selectedCheckpoint?.id, selectedCheckpoint?.beaconDataHex) {
        editedBeaconDataHex = selectedCheckpoint?.beaconDataHex.orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MapSurface(
            checkpoints = checkpoints,
            beaconSignals = beaconSignals,
            nowElapsedRealtime = nowElapsedRealtime,
            selectedCheckpointId = selectedCheckpointId,
            editable = true,
            onSelectCheckpoint = onSelectCheckpoint,
            onMoveCheckpoint = onMoveCheckpoint,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newCheckpointName,
                        onValueChange = { newCheckpointName = it },
                        label = { Text("檢核點名稱") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = newBeaconDataHex,
                        onValueChange = { newBeaconDataHex = normalizeBeaconHex(it) },
                        label = { Text("Beacon Data") },
                        singleLine = true,
                        modifier = Modifier.width(150.dp),
                    )
                }

                Button(
                    onClick = {
                        onAddCheckpoint(newCheckpointName, newBeaconDataHex)
                        newCheckpointName = ""
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("新增檢核點")
                }

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

                Column(
                    modifier = Modifier
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState()),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = editedBeaconDataHex,
                    onValueChange = onBeaconDataChange,
                    label = { Text("Beacon Data") },
                    singleLine = true,
                    enabled = checkpoint != null,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onApplyBeaconData,
                    enabled = checkpoint != null &&
                        editedBeaconDataHex.isNotBlank() &&
                        editedBeaconDataHex != checkpoint.beaconDataHex,
                ) {
                    Text("套用")
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
    selectedCheckpointId: String?,
    editable: Boolean,
    onSelectCheckpoint: (String) -> Unit,
    onMoveCheckpoint: (String, MapPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val markerSize = if (editable) 72.dp else 78.dp
    val markerSizePx = with(density) { markerSize.toPx() }

    Box(
        modifier = modifier
            .heightIn(min = 300.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MapBackground)
            .border(1.dp, MapBorder, RoundedCornerShape(8.dp))
            .onSizeChanged { mapSize = it }
            .pointerInput(editable, selectedCheckpointId, mapSize) {
                if (editable) {
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
            },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val gridStroke = 1.dp.toPx()
            val routeStroke = 3.dp.toPx()

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
                        enabled = editable,
                        onClick = { onSelectCheckpoint(checkpoint.id) },
                    )
                    .then(
                        if (editable) {
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

            if (strength > 0f) {
                drawCircle(
                    color = PinActive.copy(alpha = 0.16f + 0.28f * strength),
                    radius = effectRadius,
                    center = center,
                )
                drawCircle(
                    color = PinActive.copy(alpha = 0.55f),
                    radius = effectRadius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx() + 4.dp.toPx() * strength),
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
                color = if (strength > 0f) PinActive else PinIdle,
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
            color = Color.White.copy(alpha = 0.92f),
            shape = RoundedCornerShape(6.dp),
            shadowElevation = 1.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 70.dp),
        ) {
            Text(
                text = checkpoint.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun CheckpointSignalList(
    checkpoints: List<Checkpoint>,
    beaconSignals: Map<String, BeaconSignal>,
    nowElapsedRealtime: Long,
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
                CheckpointSignalRow(
                    checkpoint = checkpoint,
                    signal = signal,
                    strength = strength,
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
        SignalMeter(strength = strength)
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
