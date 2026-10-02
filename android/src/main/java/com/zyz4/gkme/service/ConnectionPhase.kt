package com.zyz4.gkme.service

enum class ConnectionPhase {
    IDLE,
    REGISTERING_PROFILE,
    RECONNECTING,
    LISTENING,
    DISCOVERABLE,
    CONNECTED,
    DISCONNECTED,
    ERROR,
}
