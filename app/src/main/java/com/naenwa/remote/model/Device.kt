package com.naenwa.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: String? = null,
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("user_id")
    val userId: String? = null,
    @SerialName("device_name")
    val deviceName: String = "My Mac",
    val url: String? = null,
    val status: String = "offline",
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
) {
    val isOnline: Boolean
        get() = status == "online"
}

@Serializable
data class QrCodeData(
    @SerialName("device_id")
    val deviceId: String,
    val name: String,
    val url: String
)
