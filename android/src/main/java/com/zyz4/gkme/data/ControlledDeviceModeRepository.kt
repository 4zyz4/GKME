package com.zyz4.gkme.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zyz4.gkme.model.VirtualGamepadType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.controlledModeDataStore by preferencesDataStore(name = "controlled_device_modes")

/**
 * 被控端页面按物理设备（MAC，未知时退回 IP）记录各自模拟的手柄类型，
 * 行为对齐 GKME-Windows 的 DeviceModeStore：同一台控制端重连后沿用上次选择。
 */
@Singleton
class ControlledDeviceModeRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private object Keys {
        val DEVICE_MODES = stringPreferencesKey("device_modes")
    }

    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Int>>() {}.type

    val modes: Flow<Map<String, VirtualGamepadType>> = context.controlledModeDataStore.data.map { prefs ->
        val raw: Map<String, Int> = try {
            prefs[Keys.DEVICE_MODES]?.let { gson.fromJson<Map<String, Int>>(it, mapType) } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        raw.mapNotNull { (key, ordinal) ->
            VirtualGamepadType.entries.getOrNull(ordinal)?.let { key to it }
        }.toMap()
    }

    suspend fun getModes(): Map<String, VirtualGamepadType> = modes.first()

    suspend fun setMode(groupKey: String, type: VirtualGamepadType) {
        context.controlledModeDataStore.edit { prefs ->
            val current = HashMap<String, Int>()
            try {
                prefs[Keys.DEVICE_MODES]?.let { json ->
                    val parsed = gson.fromJson<Map<String, Int>>(json, mapType)
                    if (parsed != null) current.putAll(parsed)
                }
            } catch (_: Exception) {
            }
            current[groupKey] = type.ordinal
            prefs[Keys.DEVICE_MODES] = gson.toJson(current)
        }
    }
}
