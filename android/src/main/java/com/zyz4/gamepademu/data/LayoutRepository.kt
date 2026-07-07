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
    private val layoutsDir: File
        get() {
            val dir = File(context.getExternalFilesDir(null), "layouts")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun listPresets(): List<String> {
        val files = layoutsDir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.map { it.nameWithoutExtension }.sorted()
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

    fun getDefaultPreset(): LayoutPreset {
        val json = context.resources.openRawResource(R.raw.full).bufferedReader().use { it.readText() }
        return LayoutPreset.fromJson(json) ?: LayoutPreset()
    }

    fun createDefaultPreset(): LayoutPreset {
        val preset = getDefaultPreset()
        savePreset("Default", preset)
        return preset
    }

    fun createDefaultPreset(name: String): LayoutPreset {
        val preset = createDefaultPreset()
        savePreset(name, preset)
        return preset
    }
}
