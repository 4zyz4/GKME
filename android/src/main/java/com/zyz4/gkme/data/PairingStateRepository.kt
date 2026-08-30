package com.zyz4.gkme.data

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pairingDataStore by preferencesDataStore(name = "pairing_state")

@Singleton
class PairingStateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val PAIRED_DEVICE_ADDRESS = stringPreferencesKey("paired_device_address")
        val PAIRED_DEVICE_NAME = stringPreferencesKey("paired_device_name")
    }

    val pairedDeviceAddress: Flow<String?> = context.pairingDataStore.data.map { it[Keys.PAIRED_DEVICE_ADDRESS] }
    val pairedDeviceName: Flow<String?> = context.pairingDataStore.data.map { it[Keys.PAIRED_DEVICE_NAME] }

    suspend fun savePairedDevice(device: BluetoothDevice) {
        context.pairingDataStore.edit {
            it[Keys.PAIRED_DEVICE_ADDRESS] = device.address
            it[Keys.PAIRED_DEVICE_NAME] = device.name ?: device.address
        }
    }

    suspend fun clearPairedDevice() {
        context.pairingDataStore.edit {
            it.remove(Keys.PAIRED_DEVICE_ADDRESS)
            it.remove(Keys.PAIRED_DEVICE_NAME)
        }
    }

    suspend fun getPairedDeviceAddress(): String? = pairedDeviceAddress.first()
}
