package com.zyz4.gkme.service

enum class ConnectionPhase {
    IDLE,
    REQUESTING_PERMISSIONS,
    REGISTERING_PROFILE,
    RECONNECTING,
    LISTENING,
    DISCOVERABLE,
    PAIRING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
}
