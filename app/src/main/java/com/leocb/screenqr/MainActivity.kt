package com.leocb.screenqr

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.leocb.screenqr.capture.AccessibilityCapture
import com.leocb.screenqr.capture.ProjectionSession
import com.leocb.screenqr.capture.ScreenCaptureService
import com.leocb.screenqr.model.QrPayload
import com.leocb.screenqr.tiles.CameraQrTileService
import com.leocb.screenqr.tiles.ScreenQrTileService
import com.leocb.screenqr.ui.QrResultCard
import com.leocb.screenqr.ui.theme.ScreenQrTheme
import java.text.DateFormat
import java.util.Date
import com.leocb.screenqr.util.AppLocales
import android.content.Context
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField

class MainActivity : ComponentActivity() {

    private var refreshTick by mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Start the scan only once the system dialog is out of the way, otherwise it eats
            // into the 10 second countdown.
            startScreenScan()
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScreenQrTheme {
                MainScreen(
                    activity = this@MainActivity,
                    refreshTick = refreshTick,
                    onReadScreen = {
                        if (needsNotificationPermission()) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startScreenScan()
                        }
                    },
                    onScanCamera = { startActivity(Intent(this, CameraScanActivity::class.java)) },
                )
            }
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun startScreenScan() {
        startActivity(ScanActivity.intent(this, countdownSeconds = 10))
    }

    override fun onResume() {
        super.onResume()
        refreshTick++
    }

    fun addTile(serviceClass: Class<*>) {
        val isCamera = serviceClass == CameraQrTileService::class.java
        val label = getString(if (isCamera) R.string.tile_camera_label else R.string.tile_screen_label)
        val iconRes = if (isCamera) R.drawable.ic_camera else R.drawable.ic_scan

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(
                this,
                getString(R.string.toast_add_tile_manual, label),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val statusBar = getSystemService(StatusBarManager::class.java) ?: return
        runCatching {
            statusBar.requestAddTileService(
                ComponentName(this, serviceClass),
                getString(R.string.app_name),
                Icon.createWithResource(this, iconRes),
                mainExecutor,
            ) { result ->
                if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED &&
                    result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                ) {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_add_tile_manual, label),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.onFailure {
            Toast.makeText(this, getString(R.string.toast_add_tile_failed), Toast.LENGTH_LONG).show()
        }
    }

    fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }
}

@Composable
private fun MainScreen(
    activity: MainActivity,
    refreshTick: Int,
    onReadScreen: () -> Unit,
    onScanCamera: () -> Unit,
) {
    val settings by Prefs.settings.collectAsState()
    val history by Prefs.history.collectAsState()
    val accessibilityEnabled = remember(refreshTick) { AccessibilityCapture.isEnabled(activity) }
    var selectedResult by remember { mutableStateOf<String?>(null) }
    var sessionLive by remember(refreshTick) { mutableStateOf(ProjectionSession.isActive) }

    val language = remember(refreshTick) { AppLocales.current(activity) }
    val captionBase = stringResource(R.string.action_read_screen_caption)
    val captionAccessibility = stringResource(R.string.action_read_screen_caption_accessibility)
    val captionPermission = stringResource(R.string.action_read_screen_caption_permission)
    val readCaption = when {
        accessibilityEnabled -> "$captionBase · $captionAccessibility"
        !sessionLive -> "$captionBase · $captionPermission"
        else -> captionBase
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .clipToBounds()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                    androidx.compose.material3.Icon(
                        painter = painterResource(R.drawable.ic_scan),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(10.dp).size(26.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ActionRow(
                icon = painterResource(R.drawable.ic_scan),
                title = stringResource(R.string.action_read_screen),
                caption = readCaption,
                emphasised = true,
                onClick = onReadScreen,
            )
            ActionRow(
                icon = painterResource(R.drawable.ic_camera),
                title = stringResource(R.string.action_camera),
                caption = stringResource(R.string.action_camera_caption),
                emphasised = false,
                onClick = onScanCamera,
            )

            Text(
                stringResource(R.string.tip_tiles),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionCard(title = stringResource(R.string.section_tiles)) {
                TileRow(
                    icon = painterResource(R.drawable.ic_scan),
                    title = stringResource(R.string.tile_row_screen),
                    subtitle = stringResource(R.string.tile_row_screen_subtitle, stringResource(R.string.tile_screen_label)),
                    onAdd = { activity.addTile(ScreenQrTileService::class.java) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TileRow(
                    icon = painterResource(R.drawable.ic_camera),
                    title = stringResource(R.string.tile_row_camera),
                    subtitle = stringResource(R.string.tile_row_camera_subtitle, stringResource(R.string.tile_camera_label)),
                    onAdd = { activity.addTile(CameraQrTileService::class.java) },
                )
            }

            SectionCard(title = stringResource(R.string.section_accessibility)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        text = stringResource(
                            if (accessibilityEnabled) R.string.status_enabled else R.string.status_off,
                        ),
                        active = accessibilityEnabled,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { activity.openAccessibilitySettings() }) {
                        Text(
                            stringResource(
                                if (accessibilityEnabled) R.string.action_manage else R.string.action_enable,
                            ),
                        )
                    }
                }
                Text(
                    stringResource(R.string.accessibility_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.accessibility_secure_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = stringResource(R.string.section_reading_method)) {
                EngineOption(
                    title = stringResource(R.string.engine_auto),
                    subtitle = stringResource(R.string.engine_auto_subtitle),
                    selected = settings.engine == CaptureEngine.AUTO,
                    onClick = { Prefs.setEngine(CaptureEngine.AUTO) },
                )
                EngineOption(
                    title = stringResource(R.string.engine_accessibility),
                    subtitle = stringResource(R.string.engine_accessibility_subtitle),
                    selected = settings.engine == CaptureEngine.ACCESSIBILITY,
                    onClick = { Prefs.setEngine(CaptureEngine.ACCESSIBILITY) },
                )
                EngineOption(
                    title = stringResource(R.string.engine_projection),
                    subtitle = stringResource(R.string.engine_projection_subtitle),
                    selected = settings.engine == CaptureEngine.PROJECTION,
                    onClick = { Prefs.setEngine(CaptureEngine.PROJECTION) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.setting_auto_copy),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.setting_auto_copy_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.autoCopy,
                        onCheckedChange = { Prefs.setAutoCopy(it) },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.setting_keep_session),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.setting_keep_session_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.keepSession,
                        onCheckedChange = { enabled ->
                            Prefs.setKeepSession(enabled)
                            if (!enabled) {
                                ScreenCaptureService.stop(activity)
                                sessionLive = false
                            }
                        },
                    )
                }
                if (sessionLive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(text = stringResource(R.string.status_session_on), active = true)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            ScreenCaptureService.stop(activity)
                            sessionLive = false
                        }) {
                            Text(stringResource(R.string.action_stop))
                        }
                    }
                }
            }

            if (history.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.section_history),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { Prefs.clearHistory() }) {
                                Text(stringResource(R.string.action_clear))
                            }
                        }
                        history.forEachIndexed { index, record ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            HistoryRow(record = record, onClick = { selectedResult = record.raw })
                        }
                    }
                }
            }

            SectionCard(title = stringResource(R.string.section_how)) {
                Bullet(stringResource(R.string.how_camera))
                Bullet(stringResource(R.string.how_local))
                Bullet(stringResource(R.string.how_session))
                Bullet(stringResource(R.string.how_payments))
                Bullet(stringResource(R.string.how_secure))
            }

            SectionCard(title = stringResource(R.string.section_language)) {
                Text(
                    stringResource(R.string.language_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LanguagePicker(
                    current = language,
                    onSelect = { tag -> AppLocales.set(activity, tag) },
                )
            }

            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    selectedResult?.let { raw ->
        Dialog(
            onDismissRequest = { selectedResult = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    QrResultCard(
                        payload = QrPayload.parse(raw),
                        onDismiss = { selectedResult = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(record: ScanRecord, onClick: () -> Unit) {
    val payload = remember(record.raw) { QrPayload.parse(record.raw) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.history_row,
                    stringResource(payload.kind.labelRes),
                    relativeTimeText(record.time),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = payload.resolvedValue(LocalContext.current),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun relativeTimeText(time: Long): String {
    if (time <= 0L) return ""
    val diff = System.currentTimeMillis() - time
    return when {
        diff < 60_000 -> stringResource(R.string.time_just_now)
        diff < 3_600_000 -> {
            val minutes = (diff / 60_000).toInt()
            pluralStringResource(R.plurals.time_minutes_ago, minutes, minutes)
        }
        diff < 86_400_000 -> {
            val hours = (diff / 3_600_000).toInt()
            pluralStringResource(R.plurals.time_hours_ago, hours, hours)
        }
        diff < 172_800_000 -> stringResource(R.string.time_yesterday)
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
    }
}

@Composable
private fun ActionRow(
    icon: Painter,
    title: String,
    caption: String,
    emphasised: Boolean,
    onClick: () -> Unit,
) {
    val container = if (emphasised) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface
    val content = if (emphasised) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = if (emphasised) content.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
            ) {
                androidx.compose.material3.Icon(
                    painter = icon,
                    contentDescription = null,
                    tint = if (emphasised) content else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(11.dp).size(24.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.72f),
                )
            }
            Text(
                text = "\u203a",
                style = MaterialTheme.typography.headlineSmall,
                color = content.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun TileRow(
    icon: Painter,
    title: String,
    subtitle: String,
    onAdd: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        androidx.compose.material3.Icon(
            painter = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onAdd) { Text(stringResource(R.string.action_add)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker(
    current: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        AppLocales.SYSTEM to stringResource(R.string.language_system),
        AppLocales.ENGLISH to stringResource(R.string.language_english),
        AppLocales.PORTUGUESE to stringResource(R.string.language_portuguese),
    )
    val currentLabel = options.firstOrNull { it.first == current }?.second
        ?: stringResource(R.string.language_system)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.language_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (tag, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelect(tag)
                    },
                )
            }
        }
    }
}

@Composable
private fun EngineOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, active: Boolean) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
