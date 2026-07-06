package com.zyz4.gamepademu.data

import android.content.Context
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
        return LayoutPreset.fromJson(DEFAULT_JSON) ?: LayoutPreset()
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

    companion object {
        const val DEFAULT_JSON = """{"buttons":[{"height":9,"id":"btnDpadUp","lockAspect":true,"visible":true,"width":9,"x":26,"y":13},{"height":9,"id":"btnDpadDown","lockAspect":true,"visible":true,"width":9,"x":26,"y":29},{"height":9,"id":"btnDpadLeft","lockAspect":true,"visible":true,"width":9,"x":18,"y":21},{"height":9,"id":"btnDpadRight","lockAspect":true,"visible":true,"width":9,"x":34,"y":21},{"height":10,"id":"btnY","lockAspect":true,"visible":true,"width":10,"x":100,"y":11},{"height":10,"id":"btnA","lockAspect":true,"visible":true,"width":10,"x":100,"y":27},{"height":10,"id":"btnX","lockAspect":true,"visible":true,"width":10,"x":92,"y":19},{"height":10,"id":"btnB","lockAspect":true,"visible":true,"width":10,"x":108,"y":19},{"height":17,"id":"leftJoystick","lockAspect":true,"visible":true,"width":17,"x":9,"y":33},{"height":19,"id":"rightJoystick","lockAspect":true,"visible":true,"width":19,"x":81,"y":31},{"height":9,"id":"btnLT","lockAspect":false,"visible":true,"width":16,"x":0,"y":0},{"height":9,"id":"btnLB","lockAspect":false,"visible":true,"width":16,"x":16,"y":0},{"height":9,"id":"btnRT","lockAspect":false,"visible":true,"width":15,"x":105,"y":0},{"height":9,"id":"btnRB","lockAspect":false,"visible":true,"width":16,"x":89,"y":0},{"height":9,"id":"btnHome","lockAspect":true,"visible":true,"width":9,"x":56,"y":40},{"height":9,"id":"btnMenu","lockAspect":true,"visible":true,"width":9,"x":66,"y":40},{"height":22,"id":"touchpad","lockAspect":false,"visible":true,"width":34,"x":44,"y":16},{"height":9,"id":"btnSelect","lockAspect":true,"visible":true,"width":9,"x":46,"y":40}],"version":1}"""
    }
}
