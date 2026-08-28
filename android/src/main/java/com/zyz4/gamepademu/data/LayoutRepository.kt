package com.zyz4.gamepademu.data

import android.content.Context
import com.zyz4.gamepademu.R
import com.zyz4.gamepademu.model.LayoutPreset
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LayoutRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        val BUILT_IN_PRESETS = mapOf(
            "完整控制器" to R.raw.full_con,
            "左控制器" to R.raw.left_con,
            "右控制器" to R.raw.right_con,
            "鼠标" to R.raw.mouse,
            "键盘" to R.raw.keyboard
        )
    }

    private val layoutsDir: File
        get() {
            val dir = File(context.getExternalFilesDir(null), "layouts")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun listPresets(): List<String> {
        val diskFiles = layoutsDir.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()
        val builtInOrder = BUILT_IN_PRESETS.keys.toList()
        val allNames = builtInOrder + diskFiles.filter { name -> name !in builtInOrder }
        return allNames.sortedBy { name ->
            val idx = builtInOrder.indexOf(name)
            if (idx >= 0) idx else Int.MAX_VALUE
        }
    }

    fun loadPreset(name: String): LayoutPreset? {
        val file = File(layoutsDir, "$name.json")
        if (file.exists()) {
            return try {
                LayoutPreset.fromJson(file.readText())
            } catch (e: Exception) {
                null
            }
        }

        val rawId = BUILT_IN_PRESETS[name] ?: return null
        return getPresetFromRaw(rawId)
    }

    fun savePreset(name: String, preset: LayoutPreset) {
        val file = File(layoutsDir, "$name.json")
        file.writeText(preset.toJson())
    }

    fun deletePreset(name: String) {
        val file = File(layoutsDir, "$name.json")
        if (file.exists()) file.delete()
    }

    fun renamePreset(oldName: String, newName: String) {
        val oldFile = File(layoutsDir, "$oldName.json")
        val newFile = File(layoutsDir, "$newName.json")
        if (oldFile.exists()) oldFile.renameTo(newFile)
    }

    fun hasAnyPreset(): Boolean {
        val files = layoutsDir.listFiles { f -> f.extension == "json" }
        return (files?.isNotEmpty() ?: false) || BUILT_IN_PRESETS.isNotEmpty()
    }

    fun isBuiltInPreset(name: String): Boolean = name in BUILT_IN_PRESETS

    fun getDefaultPreset(): LayoutPreset {
        val json = context.resources.openRawResource(R.raw.full_con).bufferedReader().use { it.readText() }
        return LayoutPreset.fromJson(json) ?: LayoutPreset()
    }

    fun createAllBuiltInPresets() {
        // No-op: built-in presets are loaded directly from raw resources.
    }

    private fun getPresetFromRaw(rawId: Int): LayoutPreset {
        val json = context.resources.openRawResource(rawId).bufferedReader().use { it.readText() }
        return LayoutPreset.fromJson(json) ?: LayoutPreset()
    }

    fun createDefaultPreset(name: String): LayoutPreset {
        val preset = getDefaultPreset()
        savePreset(name, preset)
        return preset
    }
}