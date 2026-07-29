package com.zyz4.gamepademu

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
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
import com.zyz4.gamepademu.model.AppSettings
import com.zyz4.gamepademu.model.FillType
import com.zyz4.gamepademu.view.AppearanceApplier
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
}

internal fun MainActivity.savePickedImage(uri: Uri, prefix: String, onSaved: (String) -> Unit) {
    val dir = File(filesDir, "appearance_images")
    dir.mkdirs()
    val filename = "${prefix}_${System.currentTimeMillis()}.jpg"
    val file = File(dir, filename)
    try {
        val inputStream = contentResolver.openInputStream(uri) ?: return
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        if (bitmap != null) {
            // Scale down if too large, keeping aspect ratio
            val maxDim = 1920f
            val scale = minOf(1f, maxDim / maxOf(bitmap.width, bitmap.height))
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val resized = if (scale < 1f) bitmap.scale(w, h, true) else bitmap
            FileOutputStream(file).use { out ->
                resized.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            onSaved(file.absolutePath)
            syncAppearanceUI()
            updateAppearancePreview()
            showToast("图片已设置")
        }
    } catch (e: Exception) {
        showToast("图片加载失败: ${e.message}")
    }
}

internal fun MainActivity.onAppearanceChange(transform: (AppSettings) -> AppSettings) {
    viewModel.updateAppearance(transform)
    gamepadLayout.applyAppearance(viewModel.settings.value)
    syncAppearanceUI()
}

// ── Setup Appearance Page ──

@SuppressLint("SetTextI18n")
internal fun MainActivity.setupAppearancePage() {
    val a = this
    a.setupAppearanceImageLaunchers()

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

    // ── Reset buttons ──
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
    a.findViewById<Button>(R.id.btnResetTpColor).setOnClickListener {
        a.onAppearanceChange { it.copy(tpColor = 0xFF121212.toInt()) }
    }
    a.findViewById<Button>(R.id.btnResetTpOutlineColor).setOnClickListener {
        a.onAppearanceChange { it.copy(tpOutlineColor = 0xFF666666.toInt()) }
    }
}

// ── Color Picker Dialog ──

internal fun MainActivity.showColorPickerDialog(currentColor: Int, onColorSelected: (Int) -> Unit) {
    val a = this
    val density = a.resources.displayMetrics.density
    val pad = (8 * density).toInt()

    val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    // ── Color picker (full width) ──
    val picker = com.zyz4.gamepademu.view.ColorPickerView(a)
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

// ── Sync Appearance UI ──

@SuppressLint("SetTextI18n")
internal fun MainActivity.syncAppearanceUI() {
    val a = this
    val s = a.viewModel.settings.value

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

    // Touchpad
    a.selectChipGroup(listOf(R.id.btnTpFillSolid, R.id.btnTpFillImage), s.tpFillType.ordinal)
    a.findViewById<Button>(R.id.btnTpColor).background = colorBg(s.tpColor)
    a.findViewById<View>(R.id.layoutTpColor).visibility = if (s.tpFillType == FillType.SOLID_COLOR) View.VISIBLE else View.GONE
    a.findViewById<View>(R.id.btnTpPickImage).visibility = if (s.tpFillType == FillType.IMAGE) View.VISIBLE else View.GONE
    a.findViewById<Button>(R.id.btnTpOutlineColor).background = colorBg(s.tpOutlineColor)
    a.findViewById<SeekBar>(R.id.seekTpOutlineWidth).progress = s.tpOutlineWidth
    a.findViewById<TextView>(R.id.tvTpOutlineWidth).text = "触摸板轮廓粗细: ${s.tpOutlineWidth}"

    a.updateAppearancePreview()
}

// ── Preview (snapshot of actual gamepad layout) ──

internal var MainActivity.previewZoomVisible: Boolean
    get() = findViewById<View>(R.id.previewZoomOverlay)?.visibility == View.VISIBLE
    set(v) { findViewById<View>(R.id.previewZoomOverlay)?.visibility = if (v) View.VISIBLE else View.GONE }

internal fun MainActivity.updateAppearancePreview() {
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
