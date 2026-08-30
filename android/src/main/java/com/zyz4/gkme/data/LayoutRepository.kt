package com.zyz4.gkme.data

import android.content.Context
import com.zyz4.gkme.R
import com.zyz4.gkme.model.LayoutPreset
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
            "鼠标" to R.raw.mouse,
            "键盘" to R.raw.keyboard
        )

        private const val CACHE_DIR_NAME = "preset_cache"
        private const val CACHE_TIMESTAMP_FILE = "cache_timestamps.json"
    }

    // Memory cache: name -> LayoutPreset
    private val memoryCache = mutableMapOf<String, LayoutPreset>()

    // Disk cache timestamp: name -> last cached timestamp
    private var diskCacheTimestamps: Map<String, Long> = emptyMap()

    private val layoutsDir: File
        get() {
            val dir = File(context.getExternalFilesDir(null), "layouts")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private val cacheDir: File
        get() {
            val dir = File(context.cacheDir, CACHE_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private val cacheTimestampsFile: File
        get() = File(cacheDir, CACHE_TIMESTAMP_FILE)

    init {
        loadCacheTimestamps()
    }

    private fun loadCacheTimestamps() {
        try {
            if (cacheTimestampsFile.exists()) {
                val json = cacheTimestampsFile.readText()
                diskCacheTimestamps = parseTimestamps(json)
            }
        } catch (_: Exception) {
            diskCacheTimestamps = emptyMap()
        }
    }

    private fun parseTimestamps(json: String): Map<String, Long> {
        try {
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Long>>() {}.type
            return gson.fromJson(json, type) ?: emptyMap()
        } catch (_: Exception) {
            return emptyMap()
        }
    }

    private fun saveCacheTimestamps() {
        try {
            val gson = com.google.gson.Gson()
            cacheTimestampsFile.writeText(gson.toJson(diskCacheTimestamps))
        } catch (_: Exception) {
        }
    }

    private fun getDiskCachePreset(name: String): LayoutPreset? {
        val cacheFile = File(cacheDir, "$name.cache")
        if (!cacheFile.exists()) return null

        val fileTimestamp = cacheFile.lastModified()
        val cachedTimestamp = diskCacheTimestamps[name] ?: return null

        // Check if the source file is newer than the cache
        val sourceFile = File(layoutsDir, "$name.json")
        if (sourceFile.exists() && sourceFile.lastModified() > cachedTimestamp) {
            // Source changed, invalidate cache
            invalidateCache(name)
            return null
        }

        // Check if raw resource changed (we use resource ID as a proxy)
        val rawId = BUILT_IN_PRESETS[name]
        if (rawId != null) {
            // For built-in presets, check if the cache timestamp matches
            // If app was updated, timestamps would be different
            if (fileTimestamp > cachedTimestamp) {
                // Cache is newer than timestamp file, something changed
                // but since built-in presets come from APK, we trust the source
            }
        }

        try {
            return LayoutPreset.fromJson(cacheFile.readText())
        } catch (_: Exception) {
            return null
        }
    }

    private fun saveToDiskCache(name: String, preset: LayoutPreset, jsonText: String) {
        try {
            val cacheFile = File(cacheDir, "$name.cache")
            cacheFile.writeText(jsonText)
            diskCacheTimestamps = diskCacheTimestamps + (name to System.currentTimeMillis())
            saveCacheTimestamps()
        } catch (_: Exception) {
        }
    }

    private fun invalidateCache(name: String) {
        try {
            val cacheFile = File(cacheDir, "$name.cache")
            if (cacheFile.exists()) cacheFile.delete()
            diskCacheTimestamps = diskCacheTimestamps - name
            saveCacheTimestamps()
        } catch (_: Exception) {
        }
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
        // 1. Check memory cache
        memoryCache[name]?.let { return it }

        // 2. Check disk cache
        getDiskCachePreset(name)?.let {
            memoryCache[name] = it
            return it
        }

        // 3. Load from source
        val preset = loadPresetFromSource(name)
        preset?.let {
            memoryCache[name] = it
        }

        return preset
    }

    private fun loadPresetFromSource(name: String): LayoutPreset? {
        val file = File(layoutsDir, "$name.json")
        if (file.exists()) {
            return try {
                val json = file.readText()
                val preset = LayoutPreset.fromJson(json)
                saveToDiskCache(name, preset, json)
                preset
            } catch (e: Exception) {
                null
            }
        }

        val rawId = BUILT_IN_PRESETS[name] ?: return null
        return getPresetFromRaw(rawId, name)
    }

    fun savePreset(name: String, preset: LayoutPreset) {
        val file = File(layoutsDir, "$name.json")
        file.writeText(preset.toJson())
        // Invalidate cache when saving
        memoryCache.remove(name)
        invalidateCache(name)
    }

    fun deletePreset(name: String) {
        val file = File(layoutsDir, "$name.json")
        if (file.exists()) file.delete()
        memoryCache.remove(name)
        invalidateCache(name)
    }

    fun renamePreset(oldName: String, newName: String) {
        val oldFile = File(layoutsDir, "$oldName.json")
        val newFile = File(layoutsDir, "$newName.json")
        if (oldFile.exists()) oldFile.renameTo(newFile)
        // Invalidate both old and new cache entries
        memoryCache.remove(oldName)
        memoryCache.remove(newName)
        invalidateCache(oldName)
        invalidateCache(newName)
    }

    fun hasAnyPreset(): Boolean {
        val files = layoutsDir.listFiles { f -> f.extension == "json" }
        return (files?.isNotEmpty() ?: false) || BUILT_IN_PRESETS.isNotEmpty()
    }

    fun isBuiltInPreset(name: String): Boolean = name in BUILT_IN_PRESETS

    fun getDefaultPreset(): LayoutPreset {
        return getPresetFromRaw(R.raw.full_con, "full_con")
    }

    fun createAllBuiltInPresets() {
        // No-op: built-in presets are loaded directly from raw resources.
    }

    private fun getPresetFromRaw(rawId: Int, name: String): LayoutPreset {
        // Check memory cache first
        memoryCache[name]?.let { return it }

        // Check disk cache
        getDiskCachePreset(name)?.let {
            memoryCache[name] = it
            return it
        }

        try {
            val json = context.resources.openRawResource(rawId).bufferedReader().use { it.readText() }
            val preset = LayoutPreset.fromJson(json)
            saveToDiskCache(name, preset, json)
            memoryCache[name] = preset
            return preset
        } catch (_: Exception) {
            return LayoutPreset()
        }
    }

    fun createDefaultPreset(name: String): LayoutPreset {
        val preset = getDefaultPreset()
        savePreset(name, preset)
        return preset
    }

    fun clearCache() {
        memoryCache.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
        diskCacheTimestamps = emptyMap()
        saveCacheTimestamps()
    }
}