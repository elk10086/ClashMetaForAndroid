package com.github.kr328.clash

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Built-in read-only viewer so YAML files do not depend on an external text editor. */
class YamlPreviewActivity : AppCompatActivity() {
    private lateinit var body: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        applyCloudTheme()
        super.onCreate(savedInstanceState)
        if (!canViewConfigurationModule()) {
            finish()
            return
        }

        val surface = resolveThemedColor(com.google.android.material.R.attr.colorSurface)
        val onSurface = resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val secondary = resolveThemedColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val fileName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "config.yaml" }

        body = FrameLayout(this).apply {
            setBackgroundColor(resolveThemedColor(android.R.attr.windowBackground))
            addView(ProgressBar(this@YamlPreviewActivity), FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(resolveThemedColor(android.R.attr.windowBackground))
            addView(LinearLayout(this@YamlPreviewActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(16), 0)
                setBackgroundColor(surface)
                elevation = dp(2).toFloat()
                addView(ImageView(this@YamlPreviewActivity).apply {
                    setImageResource(R.drawable.cloud_back)
                    setColorFilter(onSurface)
                    contentDescription = "返回"
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(13), dp(13), dp(13), dp(13))
                    setOnClickListener { onBackPressedDispatcher.onBackPressed() }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(LinearLayout(this@YamlPreviewActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@YamlPreviewActivity).apply {
                        text = "配置预览"
                        textSize = 17f
                        includeFontPadding = false
                        setTextColor(onSurface)
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(this@YamlPreviewActivity).apply {
                        text = fileName
                        textSize = 11f
                        includeFontPadding = false
                        setTextColor(secondary)
                        maxLines = 1
                    })
                }, LinearLayout.LayoutParams(0, -1, 1f))
                addView(TextView(this@YamlPreviewActivity).apply {
                    text = "只读"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(secondary)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                })
            }, LinearLayout.LayoutParams(-1, dp(64)))
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(page)

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val uri = requireNotNull(intent.data) { "文件地址无效" }
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("无法读取配置文件")
                }
            }
            showContent(result.getOrElse { "无法预览 YAML 文件\n\n${it.message ?: "读取失败"}" }, result.isFailure)
        }
    }

    private fun showContent(content: String, failed: Boolean) {
        val onSurface = resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
        val error = resolveThemedColor(com.google.android.material.R.attr.colorError)
        val text = TextView(this).apply {
            this.text = content.ifBlank { "配置文件为空" }
            textSize = 12.5f
            setTextColor(if (failed) error else onSurface)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        body.removeAllViews()
        body.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(HorizontalScrollView(this@YamlPreviewActivity).apply {
                isFillViewport = true
                addView(text)
            })
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_NAME = "file_name"
    }
}
