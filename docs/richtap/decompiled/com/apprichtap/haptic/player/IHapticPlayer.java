/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.os.VibrationAttributes
 */
package com.apprichtap.haptic.player;

import android.os.VibrationAttributes;
import com.apprichtap.haptic.player.CustomizableHapticPlayer;
import com.apprichtap.haptic.player.IHapticEffectPerformer;
import com.apprichtap.haptic.player.PlayerEventCallback;
import com.apprichtap.haptic.sync.SyncCallback;
import java.io.File;

public interface IHapticPlayer {
    public static final float SPEED_MULTIPLE_MAX = 3.0f;
    public static final float SPEED_MULTIPLE_MIN = 0.5f;

    public static IHapticPlayer create(IHapticEffectPerformer performer) {
        IHapticEffectPerformer iHapticEffectPerformer;
        return new CustomizableHapticPlayer(iHapticEffectPerformer);
    }

    public void reset();

    public void setDataSource(String var1, int var2, int var3, SyncCallback var4);

    public void setDataSource(File var1, int var2, int var3, SyncCallback var4);

    public void setDataSource(String var1, int var2, int var3, int var4, int var5, SyncCallback var6);

    public void setDataSource(int var1, int var2);

    public void setStartOffsetMillis(int var1);

    public void setOffsetRepeat(boolean var1);

    public void release();

    public void prepare();

    public void seekTo(int var1);

    public void start();

    public void stop();

    public void pause();

    public int getCurrentPosition();

    public int getDuration();

    public boolean isPlaying();

    public void setLooping(boolean var1);

    public void registerPlayerEventCallback(PlayerEventCallback var1);

    public void unregisterPlayerEventCallback();

    public void setSpeed(float var1);

    public float getSpeed();

    public void setSwitching(boolean var1);

    public boolean getSwitching();

    public void updateHapticParameter(int var1, int var2, int var3);

    public void clearPlayingTask();

    public void setVibrationAttributes(VibrationAttributes var1);
}

