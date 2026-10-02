/*
 * Decompiled with CFR 0.152.
 */
package com.apprichtap.haptic.player;

public interface PlayerEventCallback {
    public void onSeekCompleted(int var1);

    public void onPlayerStateChanged(int var1);

    default public void onError(int errorCode) {
    }
}

