package com.zyz4.gkme.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.zyz4.gkme.R
import com.zyz4.gkme.model.LayoutPreset
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LayoutRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        val BUILT_IN_PRESETS = mapOf(
            "完整控制器" to R.raw.full_con,
            "鼠标" to R.raw.mouse,
            "键盘" to R.raw.keyboard
        )

        private const val CACHE_DIR_NAME = "preset_cache"
        private const val CACHE_INDEX_FILE = "cache_index.json"

        fun computeSha256(content: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    // Memory cache: name -> LayoutPreset
    private val memoryCache = mutableMapOf<String, LayoutPreset>()

    // Disk cache index: name -> cached SHA-256
    private var diskCacheIndex: Map<String, String> = emptyMap()

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

    private val cacheIndexFile: File
        get() = File(cacheDir, CACHE_INDEX_FILE)

    private fun computeRawSha256(rawId: Int): String {
            val content = context.resources.openRawResource(rawId).bufferedReader().use { it.readText() }
            return computeSha256(content)
        }

    init {
        loadCacheIndex()
    }

    private fun loadCacheIndex() {
        try {
            if (cacheIndexFile.exists()) {
                val json = cacheIndexFile.readText()
                diskCacheIndex = parseIndex(json)
            }
        } catch (_: Exception) {
            diskCacheIndex = emptyMap()
        }
    }

    private fun parseIndex(json: String): Map<String, String> {
        try {
            val gson = Gson()
            val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
            return gson.fromJson(json, type) ?: emptyMap()
        } catch (_: Exception) {
            return emptyMap()
        }
    }

    private fun saveCacheIndex() {
        try {
            val gson = Gson()
            cacheIndexFile.writeText(gson.toJson(diskCacheIndex))
        } catch (_: Exception) {
        }
    }

    private fun getDiskCacheFile(name: String): File = File(cacheDir, "$name.cache")

    private fun getDiskCachePreset(name: String): LayoutPreset? {
        val cachedHash = diskCacheIndex[name] ?: return null
        val cacheFile = getDiskCacheFile(name)
        if (!cacheFile.exists()) {
            diskCacheIndex = diskCacheIndex - name
            saveCacheIndex()
            return null
        }

        // For built-in presets, compute current SHA-256 and compare
        val rawId = BUILT_IN_PRESETS[name]
        if (rawId != null) {
            val currentHash = computeRawSha256(rawId)
            if (currentHash != cachedHash) {
                // Resource changed (e.g. app upgrade), invalidate cache
                invalidateCache(name)
                return null
            }
        }

        // For user presets on disk, verify the source file hasn't changed
        val sourceFile = File(layoutsDir, "$name.json")
        if (sourceFile.exists() && rawId == null) {
            val sourceContent = sourceFile.readText()
            val currentHash = computeSha256(sourceContent)
            if (currentHash != cachedHash) {
                invalidateCache(name)
                return null
            }
        }

        try {
            return LayoutPreset.fromJson(cacheFile.readText())
        } catch (_: Exception) {
            invalidateCache(name)
            return null
        }
    }

    private fun saveToDiskCache(name: String, preset: LayoutPreset, jsonText: String, hash: String) {
        try {
            val cacheFile = getDiskCacheFile(name)
            cacheFile.writeText(jsonText)
            diskCacheIndex = diskCacheIndex + (name to hash)
            saveCacheIndex()
        } catch (_: Exception) {
        }
    }

    private fun invalidateCache(name: String) {
        try {
            val cacheFile = getDiskCacheFile(name)
            if (cacheFile.exists()) cacheFile.delete()
            diskCacheIndex = diskCacheIndex - name
            saveCacheIndex()
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
                val hash = computeSha256(json)
                saveToDiskCache(name, preset, json, hash)
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
            val hash = computeSha256(json)
            val preset = LayoutPreset.fromJson(json)
            saveToDiskCache(name, preset, json, hash)
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
        diskCacheIndex = emptyMap()
        saveCacheIndex()
    }
}