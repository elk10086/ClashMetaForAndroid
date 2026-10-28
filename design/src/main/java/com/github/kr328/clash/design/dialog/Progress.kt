package com.github.kr328.clash.design.dialog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.github.kr328.clash.design.databinding.DialogFetchStatusBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.ThemeTypography
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ModelProgressBarConfigure {
    var isIndeterminate: Boolean
    var text: String?
    var progress: Int
    var max: Int
}

interface ModelProgressBarScope {
    suspend fun configure(block: suspend ModelProgressBarConfigure.() -> Unit)
}

suspend fun Context.withModelProgressBar(block: suspend ModelProgressBarScope.() -> Unit) {
    val view = DialogFetchStatusBinding.inflate(this.layoutInflater)
    val dialog = MaterialAlertDialogBuilder(this)
        .setCancelable(false)
        .setView(view.root)
        .show()
    dialog.window?.decorView?.let(ThemeTypography::install)

    val configureImpl = object : ModelProgressBarConfigure {
        override var isIndeterminate: Boolean
            get() = view.progressIndicator.isIndeterminate
            set(value) {
                view.progressIndicator.isIndeterminate = value
            }
        override var text: String?
            get() = view.text.text?.toString()
            set(value) {
                view.text.text = value
                view.text.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        override var progress: Int
            get() = view.progressIndicator.progress
            set(value) {
                view.progressIndicator.setProgressCompat(value, true)
            }
        override var max: Int
            get() = view.progressIndicator.max
            set(value) {
                view.progressIndicator.max = value
            }

    }

    val scopeImpl = object : ModelProgressBarScope {
        override suspend fun configure(block: suspend ModelProgressBarConfigure.() -> Unit) {
            withContext(Dispatchers.Main) {
                configureImpl.block()
            }
        }
    }

    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes.apply { dimAmount = .28f }
        setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    try {
        scopeImpl.block()
    } finally {
        dialog.dismiss()
    }
}
