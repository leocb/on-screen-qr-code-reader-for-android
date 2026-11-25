package com.leocb.screenqr.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leocb.screenqr.R
import com.leocb.screenqr.model.QrPayload
import com.leocb.screenqr.util.Clipboard
import kotlinx.coroutines.delay

/** The result sheet shared by the screen scanner, the camera scanner and the history list. */
@Composable
fun QrResultCard(
    payload: QrPayload,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes dismissLabelRes: Int = R.string.action_close,
    onRetry: (() -> Unit)? = null,
    @StringRes retryLabelRes: Int = R.string.action_scan_again,
) {
    val context = LocalContext.current
    var copied by remember(payload.raw) { mutableStateOf(false) }
    var revealed by remember(payload.raw) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = stringResource(payload.kind.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Box(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(payload.headlineRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                TextButton(onClick = onDismiss) { Text(stringResource(dismissLabelRes)) }
            }

            SelectionContainer {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 170.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = payload.resolvedValue(context),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = if (payload.kind == QrPayload.Kind.TEXT) FontFamily.Monospace else FontFamily.Default,
                    )
                }
            }

            if (payload.fields.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    payload.fields.forEach { field ->
                        val label = stringResource(field.labelRes)
                        val value = field.valueRes?.let { stringResource(it) } ?: field.value
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = if (field.sensitive && !revealed) "•".repeat(value.length.coerceIn(4, 16))
                                    else value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = if (field.sensitive) FontFamily.Monospace else FontFamily.Default,
                                )
                            }
                            if (field.sensitive) {
                                TextButton(onClick = { revealed = !revealed }) {
                                    Text(
                                        stringResource(
                                            if (revealed) R.string.action_hide else R.string.action_show,
                                        ),
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    Clipboard.copy(context, value, sensitive = field.sensitive, label = label)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_field_copied, label),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            ) {
                                Text(stringResource(R.string.action_copy))
                            }
                        }
                    }
                }
            }

            payload.resolvedNote(context)?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (payload.openIntent != null) {
                        Button(
                            onClick = { context.openQr(payload, chooser = false) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(payload.openLabelRes ?: R.string.action_open),
                                maxLines = 1,
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            Clipboard.copy(context, payload.raw)
                            copied = true
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(if (copied) R.string.action_copied else R.string.action_copy),
                            maxLines = 1,
                        )
                    }
                }

                // Secondary actions, two per row, so nothing is ever clipped.
                val shareTitle = stringResource(R.string.share_chooser_title)
                val secondary = buildList<Pair<String, () -> Unit>> {
                    add(stringResource(R.string.action_share) to {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, payload.raw)
                        }
                        context.tryStart(Intent.createChooser(share, shareTitle))
                    })
                    if (payload.openIntent != null && payload.allowChooser) {
                        add(stringResource(R.string.action_open_with) to {
                            context.openQr(payload, chooser = true)
                        })
                    }
                    if (payload.kind == QrPayload.Kind.WIFI) {
                        add(stringResource(R.string.action_wifi_settings) to {
                            context.tryStart(Intent(Settings.ACTION_WIFI_SETTINGS))
                        })
                    }
                    if (onRetry != null) {
                        add(stringResource(retryLabelRes) to onRetry)
                    }
                }
                secondary.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (label, action) ->
                            OutlinedButton(
                                onClick = action,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(label, maxLines = 1)
                            }
                        }
                        if (row.size == 1) {
                            Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** Opens the payload, falling back to a browser URL when the target app is missing. */
private fun Context.openQr(payload: QrPayload, chooser: Boolean) {
    val target = payload.openIntent ?: return
    val intent = if (chooser) {
        Intent.createChooser(Intent(target), getString(R.string.open_with_chooser_title))
    } else {
        target
    }
    val launch = if (this !is Activity) Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent
    try {
        startActivity(launch)
        return
    } catch (e: ActivityNotFoundException) {
        // fall through
    } catch (e: SecurityException) {
        // fall through
    }

    val fallback = payload.fallbackUrl
    if (fallback != null) {
        try {
            tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
            return
        } catch (e: ActivityNotFoundException) {
            // fall through
        }
    }
    Toast.makeText(this, getString(R.string.toast_no_app), Toast.LENGTH_SHORT).show()
}

fun Context.tryStart(intent: Intent) {
    val launch = if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent
    try {
        startActivity(launch)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.toast_no_handler), Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(this, getString(R.string.toast_action_denied), Toast.LENGTH_SHORT).show()
    }
}
