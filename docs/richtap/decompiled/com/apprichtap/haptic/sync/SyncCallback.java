/*
 * Decompiled with CFR 0.152.
 */
package com.apprichtap.haptic.sync;

public interface SyncCallback {
    public int getCurrentPosition();

    default public int getDuration() {
        return -1;
    }
}

