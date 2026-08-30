package com.zyz4.gkme

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.scale
import com.zyz4.gkme.model.AppearanceProfile
import com.zyz4.gkme.model.AppSettings
import com.zyz4.gkme.model.FillType
import java.io.File
import java.io.FileOutputStream

// ── Image picker launchers ──

internal fun MainActivity.setupAppearanceImageLaunchers() {
    val a = this

    a.bgImagePickerLauncher = a.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { a.savePickedImage(it, "bg") { path -> a.viewModel.updateAppearance { it.copy(bgImagePath = path) } } }
    }
    a.btnImagePickerLauncher = a.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { a.savePickedImage(it, "btn") { path -> a.viewModel.updateAppearance { it.copy(btnImagePath = path) } } }
    }
    a.joyBaseImagePickerLauncher = a.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { a.savePickedImage(it, "joy_base") { path -> a.viewModel.updateAppearance { it.copy(joyBaseImagePath = path) } } }
    }
    a.joyCapImagePickerLauncher = a.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { a.savePickedImage(it, "joy_cap") { path -> a.viewModel.updateAppearance { it.copy(joyCapImagePath = path) } } }
    }
    a.tpImagePickerLauncher = a.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { a.savePickedImage(it, "tp") { path -> a.viewModel.updateAppearance { it.copy(tpImagePath = path) } } }
    }
    a.padImagePickerLauncher = a.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { a.savePickedImage(it, "pad") { path ->
            a.onAppearanceChange { it.copy(dpadPadImagePath = path, dpadPadFillType = FillType.IMAGE) }
        } }
    }
}

internal fun MainActivity.savePickedImage(uri: Uri, prefix: String, onSaved: (String) -> Unit) {
    val a = this
    val dir = File(a.filesDir, "appearance_images")
    dir.mkdirs()
    val filename = "${prefix}_${System.currentTimeMillis()}.jpg"
    val file = File(dir, filename)
    try {
        val inputStream = a.contentResolver.openInputStream(uri) ?: return
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        if (bitmap != null) {
            val maxDim = maxOf(a.resources.displayMetrics.widthPixels, a.resources.displayMetrics.heightPixels).toFloat()
            val scale = minOf(1f, maxDim / maxOf(bitmap.width.toFloat(), bitmap.height.toFloat()))
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val resized = if (scale < 1f) bitmap.scale(w, h, true) else bitmap
            FileOutputStream(file).use { out ->
                resized.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            onSaved(file.absolutePath)
            a.syncAppearanceUI()
            a.updateAppearancePreview()
            a.showToast("图片已设置")
        }
    } catch (e: Exception) {
        a.showToast("图片加载失败: ${e.message}")
    }
}

internal fun MainActivity.onAppearanceChange(transform: (AppSettings) -> AppSettings) {
    viewModel.updateAppearance(transform)
    applyAppearanceIfChanged(viewModel.settings.value)
    // Text auto-size and foreground bounds are resolved during the next layout pass. Request it
    // now so the one-shot preview capture registered by syncAppearanceUI() re-renders the
    // settled layout immediately after the toggle.
    gamepadLayout.requestLayout()
    syncAppearanceUI()
}

// ── Setup Appearance Page ──

@SuppressLint("SetTextI18n")
internal fun MainActivity.setupAppearancePage() {
    val a = this

    // Zoom button + tap-to-dismiss
    a.findViewById<ImageButton>(R.id.btnZoomPreview).setOnClickListener {
        a.showPreviewZoom()
    }
    a.findViewById<FrameLayout>(R.id.previewZoomOverlay).setOnClickListener {
        a.hidePreviewZoom()
    }

    fun updatePreview() {
        a.updateAppearancePreview()
    }

    // ── Icon/text max size ──
    a.findViewById<SeekBar>(R.id.seekIconMaxSize).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.findViewById<TextView>(R.id.tvIconMaxSize).text = iconMaxSizeLabel(p)
                a.onAppearanceChange { it.copy(iconMaxSize = p) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )

    // ── Background ──
    a.findViewById<Button>(R.id.btnBgFillSolid).setOnClickListener {
        a.onAppearanceChange { it.copy(bgFillType = FillType.SOLID_COLOR) }
    }
    a.findViewById<Button>(R.id.btnBgFillImage).setOnClickListener {
        a.onAppearanceChange { it.copy(bgFillType = FillType.IMAGE) }
    }
    a.findViewById<Button>(R.id.btnBgColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.bgColor) { color ->
            a.onAppearanceChange { it.copy(bgColor = color) }
        }
    }
    a.findViewById<Button>(R.id.btnBgPickImage).setOnClickListener {
        a.bgImagePickerLauncher?.launch("image/*")
    }

    // ── Buttons ──
    a.findViewById<Button>(R.id.btnBtnFillSolid).setOnClickListener {
        a.onAppearanceChange { it.copy(btnFillType = FillType.SOLID_COLOR) }
    }
    a.findViewById<Button>(R.id.btnBtnFillImage).setOnClickListener {
        a.onAppearanceChange { it.copy(btnFillType = FillType.IMAGE) }
    }
    a.findViewById<Button>(R.id.btnBtnColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.btnColor) { color ->
            a.onAppearanceChange { it.copy(btnColor = color) }
        }
    }
    a.findViewById<Button>(R.id.btnBtnPickImage).setOnClickListener {
        a.btnImagePickerLauncher?.launch("image/*")
    }
    a.findViewById<Button>(R.id.btnBtnOutlineColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.btnOutlineColor) { color ->
            a.onAppearanceChange { it.copy(btnOutlineColor = color) }
        }
    }
    a.findViewById<SeekBar>(R.id.seekBtnOutlineWidth).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.findViewById<TextView>(R.id.tvBtnOutlineWidth).text = "轮廓粗细: $p"
                a.onAppearanceChange { it.copy(btnOutlineWidth = p) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )

    // ── Joystick Base ──
    a.findViewById<Button>(R.id.btnJoyBaseFillSolid).setOnClickListener {
        a.onAppearanceChange { it.copy(joyBaseFillType = FillType.SOLID_COLOR) }
    }
    a.findViewById<Button>(R.id.btnJoyBaseFillImage).setOnClickListener {
        a.onAppearanceChange { it.copy(joyBaseFillType = FillType.IMAGE) }
    }
    a.findViewById<Button>(R.id.btnJoyBaseColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.joyBaseColor) { color ->
            a.onAppearanceChange { it.copy(joyBaseColor = color) }
        }
    }
    a.findViewById<Button>(R.id.btnJoyBasePickImage).setOnClickListener {
        a.joyBaseImagePickerLauncher?.launch("image/*")
    }
    a.findViewById<Button>(R.id.btnJoyBaseOutlineColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.joyBaseOutlineColor) { color ->
            a.onAppearanceChange { it.copy(joyBaseOutlineColor = color) }
        }
    }
    a.findViewById<SeekBar>(R.id.seekJoyBaseOutlineWidth).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.findViewById<TextView>(R.id.tvJoyBaseOutlineWidth).text = "底座轮廓粗细: $p"
                a.onAppearanceChange { it.copy(joyBaseOutlineWidth = p) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )

    // ── Joystick Cap ──
    a.findViewById<Button>(R.id.btnJoyCapFillSolid).setOnClickListener {
        a.onAppearanceChange { it.copy(joyCapFillType = FillType.SOLID_COLOR) }
    }
    a.findViewById<Button>(R.id.btnJoyCapFillImage).setOnClickListener {
        a.onAppearanceChange { it.copy(joyCapFillType = FillType.IMAGE) }
    }
    a.findViewById<Button>(R.id.btnJoyCapColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.joyCapColor) { color ->
            a.onAppearanceChange { it.copy(joyCapColor = color) }
        }
    }
    a.findViewById<Button>(R.id.btnJoyCapPickImage).setOnClickListener {
        a.joyCapImagePickerLauncher?.launch("image/*")
    }
    a.findViewById<Button>(R.id.btnJoyCapOutlineColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.joyCapOutlineColor) { color ->
            a.onAppearanceChange { it.copy(joyCapOutlineColor = color) }
        }
    }
    a.findViewById<SeekBar>(R.id.seekJoyCapOutlineWidth).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.findViewById<TextView>(R.id.tvJoyCapOutlineWidth).text = "摇杆帽轮廓粗细: $p"
                a.onAppearanceChange { it.copy(joyCapOutlineWidth = p) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )

    // ── Trigger Area ──
    a.findViewById<Button>(R.id.btnJoyTriggerOutlineColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.joyTriggerOutlineColor) { color ->
            a.onAppearanceChange { it.copy(joyTriggerOutlineColor = color) }
        }
    }
    a.findViewById<SeekBar>(R.id.seekJoyTriggerOutlineWidth).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.findViewById<TextView>(R.id.tvJoyTriggerOutlineWidth).text = "触发区域粗细: $p"
                a.onAppearanceChange { it.copy(joyTriggerOutlineWidth = p) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )

    // ── Touchpad ──
    a.findViewById<Button>(R.id.btnTpFillSolid).setOnClickListener {
        a.onAppearanceChange { it.copy(tpFillType = FillType.SOLID_COLOR) }
    }
    a.findViewById<Button>(R.id.btnTpFillImage).setOnClickListener {
        a.onAppearanceChange { it.copy(tpFillType = FillType.IMAGE) }
    }
    a.findViewById<Button>(R.id.btnTpColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.tpColor) { color ->
            a.onAppearanceChange { it.copy(tpColor = color) }
        }
    }
    a.findViewById<Button>(R.id.btnTpPickImage).setOnClickListener {
        a.tpImagePickerLauncher?.launch("image/*")
    }
    a.findViewById<Button>(R.id.btnTpOutlineColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.tpOutlineColor) { color ->
            a.onAppearanceChange { it.copy(tpOutlineColor = color) }
        }
    }
    a.findViewById<SeekBar>(R.id.seekTpOutlineWidth).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.findViewById<TextView>(R.id.tvTpOutlineWidth).text = "触摸板轮廓粗细: $p"
                a.onAppearanceChange { it.copy(tpOutlineWidth = p) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )

    // ── Touchpad Extended Range ──
    a.findViewById<Button>(R.id.btnTpTriggerOutlineColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.tpTriggerOutlineColor) { color ->
            a.onAppearanceChange { it.copy(tpTriggerOutlineColor = color) }
        }
    }
    a.findViewById<SeekBar>(R.id.seekTpTriggerOutlineWidth).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.findViewById<TextView>(R.id.tvTpTriggerOutlineWidth).text = "区域粗细: $p"
                a.onAppearanceChange { it.copy(tpTriggerOutlineWidth = p) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )

    // ── 一体十字键/自定义按键盘 ──
    a.findViewById<Button>(R.id.btnPadFillSolid).setOnClickListener {
        a.onAppearanceChange { it.copy(dpadPadFillType = FillType.SOLID_COLOR) }
    }
    a.findViewById<Button>(R.id.btnPadFillImage).setOnClickListener {
        a.onAppearanceChange { it.copy(dpadPadFillType = FillType.IMAGE) }
    }
    a.findViewById<Button>(R.id.btnPadColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.dpadPadColor) { color ->
            a.onAppearanceChange { it.copy(dpadPadColor = color) }
        }
    }
    a.findViewById<Button>(R.id.btnPadPickImage).setOnClickListener {
        a.padImagePickerLauncher?.launch("image/*")
    }
    a.findViewById<Button>(R.id.btnPadBorderColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.dpadPadOutlineColor) { color ->
            a.onAppearanceChange { it.copy(dpadPadOutlineColor = color) }
        }
    }
    a.findViewById<SeekBar>(R.id.seekPadBorderWidth).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.findViewById<TextView>(R.id.tvPadBorderWidth).text = "控件轮廓粗细: $p"
                a.onAppearanceChange { it.copy(dpadPadOutlineWidth = p) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )

    // ── 触发区域（一体十字键/自定义按键盘） ──
    a.findViewById<Button>(R.id.btnPadTriggerOutlineColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.dpadPadTriggerOutlineColor) { color ->
            a.onAppearanceChange { it.copy(dpadPadTriggerOutlineColor = color) }
        }
    }
    a.findViewById<SeekBar>(R.id.seekPadTriggerOutlineWidth).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.findViewById<TextView>(R.id.tvPadTriggerOutlineWidth).text = "触发区域轮廓粗细: $p"
                a.onAppearanceChange { it.copy(dpadPadTriggerOutlineWidth = p) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )

    // ── Reset buttons ──
    a.findViewById<Button>(R.id.btnLinearTriggerBoxOutlineColor).setOnClickListener {
        a.showColorPickerDialog(a.viewModel.settings.value.linearTriggerBoxOutlineColor) { color ->
            a.onAppearanceChange { it.copy(linearTriggerBoxOutlineColor = color) }
        }
    }
    a.findViewById<SeekBar>(R.id.seekLinearTriggerBoxOutlineWidth).setOnSeekBarChangeListener(
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                a.findViewById<TextView>(R.id.tvLinearTriggerBoxOutlineWidth).text = "线框粗细: $p"
                a.onAppearanceChange { it.copy(linearTriggerBoxOutlineWidth = p) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
    )
    a.findViewById<Button>(R.id.btnResetBgColor).setOnClickListener {
        a.onAppearanceChange { it.copy(bgColor = 0xFF000000.toInt()) }
    }
    a.findViewById<Button>(R.id.btnResetBtnColor).setOnClickListener {
        a.onAppearanceChange { it.copy(btnColor = 0xFF1A1A1A.toInt()) }
    }
    a.findViewById<Button>(R.id.btnResetBtnOutlineColor).setOnClickListener {
        a.onAppearanceChange { it.copy(btnOutlineColor = 0xFF666666.toInt()) }
    }
    a.findViewById<Button>(R.id.btnResetJoyBaseColor).setOnClickListener {
        a.onAppearanceChange { it.copy(joyBaseColor = -0xdddddd) }
    }
    a.findViewById<Button>(R.id.btnResetJoyBaseOutlineColor).setOnClickListener {
        a.onAppearanceChange { it.copy(joyBaseOutlineColor = -0xaaaaab) }
    }
    a.findViewById<Button>(R.id.btnResetJoyCapColor).setOnClickListener {
        a.onAppearanceChange { it.copy(joyCapColor = -0xaaaaab) }
    }
    a.findViewById<Button>(R.id.btnResetJoyCapOutlineColor).setOnClickListener {
        a.onAppearanceChange { it.copy(joyCapOutlineColor = -0x888889) }
    }
    a.findViewById<Button>(R.id.btnResetJoyTriggerOutlineColor).setOnClickListener {
        a.onAppearanceChange { it.copy(joyTriggerOutlineColor = -0x666667) }
    }
    a.findViewById<Button>(R.id.btnResetLinearTriggerBoxOutlineColor).setOnClickListener {
        a.onAppearanceChange { it.copy(linearTriggerBoxOutlineColor = 0xFF888888.toInt()) }
    }
    a.findViewById<Button>(R.id.btnResetTpColor).setOnClickListener {
        a.onAppearanceChange { it.copy(tpColor = 0xFF121212.toInt()) }
    }
    a.findViewById<Button>(R.id.btnResetTpOutlineColor).setOnClickListener {
        a.onAppearanceChange { it.copy(tpOutlineColor = 0xFF666666.toInt()) }
    }
    a.findViewById<Button>(R.id.btnResetTpTriggerOutlineColor).setOnClickListener {
        a.onAppearanceChange { it.copy(tpTriggerOutlineColor = -0x666667) }
    }
    a.findViewById<Button>(R.id.btnResetPadColor).setOnClickListener {
        a.onAppearanceChange { it.copy(dpadPadColor = 0xFF1A1A1A.toInt()) }
    }
    a.findViewById<Button>(R.id.btnResetPadBorderColor).setOnClickListener {
        a.onAppearanceChange { it.copy(dpadPadOutlineColor = 0xFF666666.toInt()) }
    }
    a.findViewById<Button>(R.id.btnResetPadTriggerOutlineColor).setOnClickListener {
        a.onAppearanceChange { it.copy(dpadPadTriggerOutlineColor = -0x666667) }
    }

    // ── Export / Import Appearance ──
    a.findViewById<Button>(R.id.btnExportAppearance).setOnClickListener {
        a.exportAppearanceLauncher.launch("appearance_profile.json")
    }
    a.findViewById<Button>(R.id.btnImportAppearance).setOnClickListener {
        a.importAppearanceLauncher.launch(arrayOf("application/json", "*/*"))
    }
}

// ── Color Picker Dialog ──

internal fun MainActivity.showColorPickerDialog(currentColor: Int, onColorSelected: (Int) -> Unit) {
    val a = this
    val density = a.resources.displayMetrics.density
    val pad = (8 * density).toInt()

    val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    // ── Color picker (full width) ──
    val picker = com.zyz4.gkme.view.ColorPickerView(a)
    picker.color = currentColor
    root.addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (200 * density).toInt()))

    // ── Bottom row: swatches + hex input ──
    val previewSize = (28 * density).toInt()

    fun swatchView(color: Int): View = View(a).apply {
        layoutParams = LinearLayout.LayoutParams(previewSize, previewSize)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(color)
            setStroke(1, -0x1)
        }
    }

    val oldSwatch = swatchView(currentColor)
    val arrow = TextView(a).apply {
        text = "→"
        textSize = 16f
        setTextColor(-0x1)
        setPadding(pad, 0, pad, 0)
    }
    val newSwatch = swatchView(currentColor)
    val hexInput = android.widget.EditText(a).apply {
        textSize = 14f
        gravity = android.view.Gravity.CENTER
        setTextColor(-0x1)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(0xFF2A2A2A.toInt())
            setStroke(1, -0x555556)
        }
        setPadding(pad, pad, pad, pad)
    }

    val bottomRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, pad, 0, 0)
        addView(oldSwatch, LinearLayout.LayoutParams(previewSize, previewSize))
        addView(arrow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(newSwatch, LinearLayout.LayoutParams(previewSize, previewSize))
        addView(hexInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = pad
        })
    }
    root.addView(bottomRow)

    // ── Wire up ──
    fun updateHex() { hexInput.setText(String.format("#%06X", 0xFFFFFF and currentColor)) }
    updateHex()
    fun applySwatchColor(v: View, c: Int) {
        v.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(c)
            setStroke(1, -0x1)
        }
    }
    picker.onColorChanged = { c ->
        applySwatchColor(newSwatch, c)
        hexInput.setText(String.format("#%06X", 0xFFFFFF and c))
    }
    hexInput.setOnEditorActionListener { v, _, _ ->
        val text = v.text.toString().removePrefix("#")
        val c = try { Integer.parseInt(text, 16) or -0x1000000 } catch (_: Exception) { return@setOnEditorActionListener true }
        picker.color = c
        applySwatchColor(newSwatch, c)
        true
    }

    CustomDialog.showCustomView(a, "选择颜色", root, negativeText = "取消",
        positiveText = "确认", onPositive = { onColorSelected(picker.color) },
        scrollable = true)
}

private fun colorBg(c: Int) = android.graphics.drawable.GradientDrawable().apply {
    shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = 0f; setColor(c); setStroke(1, -0x1)
}

internal fun iconMaxSizeLabel(value: Int): String =
    if (value >= 100) "最大图标大小: 无限" else "最大图标大小: ${value.coerceAtLeast(0)}sp"

// ── Sync Appearance UI ──

@SuppressLint("SetTextI18n")
internal fun MainActivity.syncAppearanceUI() {
    val a = this
    val s = a.viewModel.settings.value

    // Icon/text max size
    a.findViewById<SeekBar>(R.id.seekIconMaxSize).progress = s.iconMaxSize.coerceIn(0, 100)
    a.findViewById<TextView>(R.id.tvIconMaxSize).text = iconMaxSizeLabel(s.iconMaxSize)

    // Background
    a.selectChipGroup(listOf(R.id.btnBgFillSolid, R.id.btnBgFillImage), s.bgFillType.ordinal)
    a.findViewById<Button>(R.id.btnBgColor).background = colorBg(s.bgColor)
    a.findViewById<View>(R.id.layoutBgColor).visibility = if (s.bgFillType == FillType.SOLID_COLOR) View.VISIBLE else View.GONE
    a.findViewById<View>(R.id.btnBgPickImage).visibility = if (s.bgFillType == FillType.IMAGE) View.VISIBLE else View.GONE

    // Buttons
    a.selectChipGroup(listOf(R.id.btnBtnFillSolid, R.id.btnBtnFillImage), s.btnFillType.ordinal)
    a.findViewById<Button>(R.id.btnBtnColor).background = colorBg(s.btnColor)
    a.findViewById<View>(R.id.layoutBtnColor).visibility = if (s.btnFillType == FillType.SOLID_COLOR) View.VISIBLE else View.GONE
    a.findViewById<View>(R.id.btnBtnPickImage).visibility = if (s.btnFillType == FillType.IMAGE) View.VISIBLE else View.GONE
    a.findViewById<Button>(R.id.btnBtnOutlineColor).background = colorBg(s.btnOutlineColor)
    a.findViewById<SeekBar>(R.id.seekBtnOutlineWidth).progress = s.btnOutlineWidth
    a.findViewById<TextView>(R.id.tvBtnOutlineWidth).text = "轮廓粗细: ${s.btnOutlineWidth}"

    // Joystick Base
    a.selectChipGroup(listOf(R.id.btnJoyBaseFillSolid, R.id.btnJoyBaseFillImage), s.joyBaseFillType.ordinal)
    a.findViewById<Button>(R.id.btnJoyBaseColor).background = colorBg(s.joyBaseColor)
    a.findViewById<View>(R.id.layoutJoyBaseColor).visibility = if (s.joyBaseFillType == FillType.SOLID_COLOR) View.VISIBLE else View.GONE
    a.findViewById<View>(R.id.btnJoyBasePickImage).visibility = if (s.joyBaseFillType == FillType.IMAGE) View.VISIBLE else View.GONE
    a.findViewById<Button>(R.id.btnJoyBaseOutlineColor).background = colorBg(s.joyBaseOutlineColor)
    a.findViewById<SeekBar>(R.id.seekJoyBaseOutlineWidth).progress = s.joyBaseOutlineWidth
    a.findViewById<TextView>(R.id.tvJoyBaseOutlineWidth).text = "底座轮廓粗细: ${s.joyBaseOutlineWidth}"

    // Joystick Cap
    a.selectChipGroup(listOf(R.id.btnJoyCapFillSolid, R.id.btnJoyCapFillImage), s.joyCapFillType.ordinal)
    a.findViewById<Button>(R.id.btnJoyCapColor).background = colorBg(s.joyCapColor)
    a.findViewById<View>(R.id.layoutJoyCapColor).visibility = if (s.joyCapFillType == FillType.SOLID_COLOR) View.VISIBLE else View.GONE
    a.findViewById<View>(R.id.btnJoyCapPickImage).visibility = if (s.joyCapFillType == FillType.IMAGE) View.VISIBLE else View.GONE
    a.findViewById<Button>(R.id.btnJoyCapOutlineColor).background = colorBg(s.joyCapOutlineColor)
    a.findViewById<SeekBar>(R.id.seekJoyCapOutlineWidth).progress = s.joyCapOutlineWidth
    a.findViewById<TextView>(R.id.tvJoyCapOutlineWidth).text = "摇杆帽轮廓粗细: ${s.joyCapOutlineWidth}"

    // Trigger Area
    a.findViewById<Button>(R.id.btnJoyTriggerOutlineColor).background = colorBg(s.joyTriggerOutlineColor)
    a.findViewById<SeekBar>(R.id.seekJoyTriggerOutlineWidth).progress = s.joyTriggerOutlineWidth
    a.findViewById<TextView>(R.id.tvJoyTriggerOutlineWidth).text = "触发区域粗细: ${s.joyTriggerOutlineWidth}"

    // Linear Trigger Box
    a.findViewById<Button>(R.id.btnLinearTriggerBoxOutlineColor).background = colorBg(s.linearTriggerBoxOutlineColor)
    a.findViewById<SeekBar>(R.id.seekLinearTriggerBoxOutlineWidth).progress = s.linearTriggerBoxOutlineWidth
    a.findViewById<TextView>(R.id.tvLinearTriggerBoxOutlineWidth).text = "线框粗细: ${s.linearTriggerBoxOutlineWidth}"

    // Touchpad
    a.selectChipGroup(listOf(R.id.btnTpFillSolid, R.id.btnTpFillImage), s.tpFillType.ordinal)
    a.findViewById<Button>(R.id.btnTpColor).background = colorBg(s.tpColor)
    a.findViewById<View>(R.id.layoutTpColor).visibility = if (s.tpFillType == FillType.SOLID_COLOR) View.VISIBLE else View.GONE
    a.findViewById<View>(R.id.btnTpPickImage).visibility = if (s.tpFillType == FillType.IMAGE) View.VISIBLE else View.GONE
    a.findViewById<Button>(R.id.btnTpOutlineColor).background = colorBg(s.tpOutlineColor)
    a.findViewById<SeekBar>(R.id.seekTpOutlineWidth).progress = s.tpOutlineWidth
    a.findViewById<TextView>(R.id.tvTpOutlineWidth).text = "触摸板轮廓粗细: ${s.tpOutlineWidth}"

    // Touchpad Extended Range
    a.findViewById<Button>(R.id.btnTpTriggerOutlineColor).background = colorBg(s.tpTriggerOutlineColor)
    a.findViewById<SeekBar>(R.id.seekTpTriggerOutlineWidth).progress = s.tpTriggerOutlineWidth
    a.findViewById<TextView>(R.id.tvTpTriggerOutlineWidth).text = "区域粗细: ${s.tpTriggerOutlineWidth}"

    // 一体十字键/自定义按键盘
    val dpadPadAppearance = s
    a.selectChipGroup(listOf(R.id.btnPadFillSolid, R.id.btnPadFillImage), dpadPadAppearance.dpadPadFillType.ordinal)
    a.findViewById<Button>(R.id.btnPadColor).background = colorBg(dpadPadAppearance.dpadPadColor)
    a.findViewById<View>(R.id.layoutPadColor).visibility = if (dpadPadAppearance.dpadPadFillType == FillType.SOLID_COLOR) View.VISIBLE else View.GONE
    a.findViewById<View>(R.id.btnPadPickImage).visibility = if (dpadPadAppearance.dpadPadFillType == FillType.IMAGE) View.VISIBLE else View.GONE
    a.findViewById<Button>(R.id.btnPadBorderColor).background = colorBg(dpadPadAppearance.dpadPadOutlineColor)
    a.findViewById<SeekBar>(R.id.seekPadBorderWidth).progress = dpadPadAppearance.dpadPadOutlineWidth
    a.findViewById<TextView>(R.id.tvPadBorderWidth).text = "控件轮廓粗细: ${dpadPadAppearance.dpadPadOutlineWidth}"

    // 触发区域（一体十字键/自定义按键盘）
    a.findViewById<Button>(R.id.btnPadTriggerOutlineColor).background = colorBg(dpadPadAppearance.dpadPadTriggerOutlineColor)
    a.findViewById<SeekBar>(R.id.seekPadTriggerOutlineWidth).progress = dpadPadAppearance.dpadPadTriggerOutlineWidth
    a.findViewById<TextView>(R.id.tvPadTriggerOutlineWidth).text = "触发区域轮廓粗细: ${dpadPadAppearance.dpadPadTriggerOutlineWidth}"

    if (a.currentSettingsCategory == 2) a.updateAppearancePreview()
}

// ── Preview (snapshot of actual gamepad layout) ──

internal var MainActivity.previewZoomVisible: Boolean
    get() = findViewById<View>(R.id.previewZoomOverlay)?.visibility == View.VISIBLE
    set(v) { findViewById<View>(R.id.previewZoomOverlay)?.visibility = if (v) View.VISIBLE else View.GONE }

internal fun MainActivity.renderAppearancePreview() {
    val a = this
    val gl = a.gamepadLayout
    if (gl.width <= 0 || gl.height <= 0) return
    val previewImg = a.findViewById<ImageView>(R.id.previewImage) ?: return
    val targetW = previewImg.measuredWidth.coerceAtLeast(400)
    val targetH = previewImg.measuredHeight.coerceAtLeast(240)
    val scale = minOf(targetW.toFloat() / gl.width, targetH.toFloat() / gl.height, 1f)
    val bmpW = (gl.width * scale).toInt().coerceAtLeast(1)
    val bmpH = (gl.height * scale).toInt().coerceAtLeast(1)

    val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.scale(scale, scale)
    gl.draw(canvas)

    // Recycle old bitmaps
    val prevImg = a.findViewById<ImageView>(R.id.previewImage)
    val oldTag = prevImg?.tag as? Bitmap
    if (oldTag != null && oldTag !== bmp) oldTag.recycle()
    prevImg?.setImageBitmap(bmp)
    prevImg?.tag = bmp

    // Also update the zoom overlay image if visible
    if (a.previewZoomVisible) {
        val zoomImg = a.findViewById<ImageView>(R.id.zoomImage)
        val oldZoomTag = zoomImg?.tag as? Bitmap
        if (oldZoomTag != null && oldZoomTag !== bmp) oldZoomTag.recycle()
        zoomImg?.setImageBitmap(bmp)
        zoomImg?.tag = bmp
    }
}

internal fun MainActivity.updateAppearancePreview() {
    val a = this
    val gl = a.gamepadLayout
    if (gl.width <= 0 || gl.height <= 0) {
        val vto = gl.viewTreeObserver
        if (vto.isAlive) {
            vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    gl.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (gl.width > 0 && gl.height > 0) a.renderAppearancePreview()
                }
            })
        }
        return
    }
    a.renderAppearancePreview()
    // Re-capture once the next layout pass finishes so that text auto-size
    // and adaptive padding (applied asynchronously) are reflected.
    gl.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
        ) {
            gl.removeOnLayoutChangeListener(this)
            if (gl.width > 0 && gl.height > 0) a.renderAppearancePreview()
        }
    })
}

internal fun MainActivity.showPreviewZoom() {
    val a = this
    val overlay = a.findViewById<FrameLayout>(R.id.previewZoomOverlay)
    if (overlay != null) {
        overlay.visibility = View.VISIBLE
        a.updateAppearancePreview()
        CustomDialog.showToast(a, "点击任意处退出")
    }
}

internal fun MainActivity.hidePreviewZoom() {
    val a = this
    val overlay = a.findViewById<FrameLayout>(R.id.previewZoomOverlay)
    overlay?.visibility = View.GONE
}

// ── Export / Import Appearance Settings ──

internal fun MainActivity.exportAppearanceToUri(uri: Uri) {
    val a = this
    try {
        val settings = a.viewModel.settings.value
        val imageDir = File(a.filesDir, "appearance_images")

        // Collect existing image files and build path -> relative name mapping
        val imageNames = mutableMapOf<String, String>()
        if (imageDir.exists()) {
            imageDir.listFiles()?.forEach { file ->
                imageNames[file.absolutePath] = file.name
            }
        }

        val profile = AppearanceProfile.fromAppSettingsWithImageNames(settings, imageNames)
        val json = AppearanceProfile.toJson(profile)

        // Always export as zip
        val tempZip = File(a.cacheDir, "appearance_export_${System.currentTimeMillis()}.zip").also { it.delete() }

        java.util.zip.ZipOutputStream(FileOutputStream(tempZip)).use { zos ->
            // Add JSON file
            val jsonEntry = java.util.zip.ZipEntry("appearance_profile.json")
            zos.putNextEntry(jsonEntry)
            zos.write(json.toByteArray())
            zos.closeEntry()

            // Add all image files from appearance_images directory
            if (imageDir.exists()) {
                imageDir.listFiles()?.forEach { imageFile ->
                    val imageEntry = java.util.zip.ZipEntry("images/${imageFile.name}")
                    zos.putNextEntry(imageEntry)
                    imageFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }

        a.contentResolver.openOutputStream(uri)?.use { out ->
            tempZip.inputStream().use { it.copyTo(out) }
        }
        tempZip.delete()

        a.showToast("外观设置导出成功")
    } catch (e: Exception) {
        a.showToast("导出失败: ${e.message}")
    }
}

internal fun MainActivity.importAppearanceFromUri(uri: Uri) {
    val a = this
    try {
        val contentResolver = a.contentResolver
        val tempFile = File(a.cacheDir, "appearance_import_${System.currentTimeMillis()}.tmp")

        // Copy URI content to temp file so we can read it multiple times
        contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { out ->
                input.copyTo(out)
            }
        }

        // Extract JSON from zip
        var jsonText: String? = null
        java.util.zip.ZipInputStream(tempFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".json")) {
                    jsonText = zis.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                entry = zis.nextEntry
            }
        }

        if (jsonText == null) {
            a.showToast("无法读取外观配置文件")
            return
        }

        val profile = AppearanceProfile.fromJson(jsonText!!)

        // Extract images from zip
        val imageDir = File(a.filesDir, "appearance_images")
        imageDir.mkdirs()

        java.util.zip.ZipInputStream(tempFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("images/")) {
                    val fileName = entry.name.substringAfterLast("/")
                    val outFile = File(imageDir, fileName).also { it.delete() }
                    FileOutputStream(outFile).use { out ->
                        zis.copyTo(out)
                    }
                }
                entry = zis.nextEntry
            }
        }

        // Convert ordinal back to FillType
        fun fillTypeFromOrdinal(ord: Int) = FillType.entries.getOrElse(ord) { FillType.SOLID_COLOR }

        // Build transform function to update only appearance settings
        val settings = a.viewModel.settings.value
        val newSettings = settings.copy(
            bgFillType = fillTypeFromOrdinal(profile.bgFillType),
            bgColor = profile.bgColor,
            bgImagePath = profile.bgImagePath?.let { File(a.filesDir, "appearance_images").resolve(it).absolutePath },
            btnFillType = fillTypeFromOrdinal(profile.btnFillType),
            btnColor = profile.btnColor,
            btnImagePath = profile.btnImagePath?.let { File(a.filesDir, "appearance_images").resolve(it).absolutePath },
            btnOutlineColor = profile.btnOutlineColor,
            btnOutlineWidth = profile.btnOutlineWidth,
            joyBaseFillType = fillTypeFromOrdinal(profile.joyBaseFillType),
            joyBaseColor = profile.joyBaseColor,
            joyBaseImagePath = profile.joyBaseImagePath?.let { File(a.filesDir, "appearance_images").resolve(it).absolutePath },
            joyBaseOutlineColor = profile.joyBaseOutlineColor,
            joyBaseOutlineWidth = profile.joyBaseOutlineWidth,
            joyCapFillType = fillTypeFromOrdinal(profile.joyCapFillType),
            joyCapColor = profile.joyCapColor,
            joyCapImagePath = profile.joyCapImagePath?.let { File(a.filesDir, "appearance_images").resolve(it).absolutePath },
            joyCapOutlineColor = profile.joyCapOutlineColor,
            joyCapOutlineWidth = profile.joyCapOutlineWidth,
            joyTriggerOutlineColor = profile.joyTriggerOutlineColor,
            joyTriggerOutlineWidth = profile.joyTriggerOutlineWidth,
            tpTriggerOutlineColor = profile.tpTriggerOutlineColor,
            tpTriggerOutlineWidth = profile.tpTriggerOutlineWidth,
            linearTriggerBoxOutlineColor = profile.linearTriggerBoxOutlineColor,
            linearTriggerBoxOutlineWidth = profile.linearTriggerBoxOutlineWidth,
            tpFillType = fillTypeFromOrdinal(profile.tpFillType),
            tpColor = profile.tpColor,
            tpImagePath = profile.tpImagePath?.let { File(a.filesDir, "appearance_images").resolve(it).absolutePath },
            tpOutlineColor = profile.tpOutlineColor,
            tpOutlineWidth = profile.tpOutlineWidth,
            dpadPadFillType = fillTypeFromOrdinal(profile.dpadPadFillType),
            dpadPadColor = profile.dpadPadColor,
            dpadPadImagePath = profile.dpadPadImagePath?.let { File(a.filesDir, "appearance_images").resolve(it).absolutePath },
            dpadPadOutlineColor = profile.dpadPadOutlineColor,
            dpadPadOutlineWidth = profile.dpadPadOutlineWidth,
            dpadPadTriggerOutlineColor = profile.dpadPadTriggerOutlineColor,
            dpadPadTriggerOutlineWidth = profile.dpadPadTriggerOutlineWidth,
            iconMaxSize = profile.iconMaxSize,
        )

        // Save settings
        a.viewModel.updateAppearance { newSettings }

        // Apply appearance
        a.applyAppearanceIfChanged(newSettings)
        a.syncAppearanceUI()
        a.updateAppearancePreview()
        a.showToast("外观设置导入成功")
    } catch (e: Exception) {
        a.showToast("导入失败: ${e.message}")
    }
}
