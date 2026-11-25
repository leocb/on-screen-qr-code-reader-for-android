package com.leocb.screenqr.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.leocb.screenqr.R

object Clipboard {

    /** Puts [value] on the clipboard. Sensitive values are flagged so the system hides previews. */
    fun copy(context: Context, value: String, sensitive: Boolean = false, label: String? = null) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label ?: context.getString(R.string.clip_label_qr), value)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        runCatching { manager.setPrimaryClip(clip) }
    }

    @Suppress("unused")
    fun isSensitiveSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
