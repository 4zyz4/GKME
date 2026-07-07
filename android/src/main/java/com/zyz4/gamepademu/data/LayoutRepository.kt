package com.zyz4.gamepademu.data

import android.content.Context
import com.zyz4.gamepademu.R
import com.zyz4.gamepademu.model.ButtonPosition
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
            "完整布局" to R.raw.full,
            "左控制器" to R.raw.left,
            "右控制器" to R.raw.right,
        )
    }

    private val layoutsDir: File
        get() {
            val dir = File(context.getExternalFilesDir(null), "layouts")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun listPresets(): List<String> {
        val files = layoutsDir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        val builtInOrder = BUILT_IN_PRESETS.keys.toList()
        return files.map { it.nameWithoutExtension }.sortedBy { name ->
            val idx = builtInOrder.indexOf(name)
            if (idx >= 0) idx else Int.MAX_VALUE
        }
    }

    fun loadPreset(name: String): LayoutPreset? {
        val file = File(layoutsDir, "$name.json")
        if (!file.exists()) return null
        return try {
            LayoutPreset.fromJson(file.readText())
        } catch (e: Exception) {
            null
        }
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
        val files = layoutsDir.listFiles { f -> f.extension == "json" } ?: return false
        return files.isNotEmpty()
    }

    fun isBuiltInPreset(name: String): Boolean = name in BUILT_IN_PRESETS

    fun getDefaultPreset(): LayoutPreset {
        val json = context.resources.openRawResource(R.raw.full).bufferedReader().use { it.readText() }
        return LayoutPreset.fromJson(json) ?: LayoutPreset()
    }

    fun createAllBuiltInPresets() {
        val legacyFile = File(layoutsDir, "Default.json")
        val fullFile = File(layoutsDir, "完整布局.json")
        if (legacyFile.exists() && !fullFile.exists()) {
            legacyFile.renameTo(fullFile)
        }
        for ((name, rawId) in BUILT_IN_PRESETS) {
            val file = File(layoutsDir, "$name.json")
            if (!file.exists()) {
                val preset = getPresetFromRaw(rawId)
                savePreset(name, preset)
            }
        }
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
