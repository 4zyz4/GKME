package com.zyz4.gkme.di

import android.content.Context
import com.zyz4.gkme.data.PairingStateRepository
import com.zyz4.gkme.data.SettingsRepository
import com.zyz4.gkme.service.ConnectionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideConnectionManager(
        @ApplicationContext context: Context,
        pairingStateRepository: PairingStateRepository,
        settingsRepository: SettingsRepository,
    ): ConnectionManager = ConnectionManager(context, pairingStateRepository, settingsRepository)
}
