package dev.sushnt.journal.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// These classes mirror the JSON the Go server sends and expects.
// @SerialName connects the server's snake_case names to Kotlin's camelCase.

@Serializable
data class SignupRequest(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String = "",
)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class SessionDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_at") val expiresAt: String = "",
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

/** One entry as it travels to and from the server. Timestamps are ISO-8601 text. */
@Serializable
data class SyncChangeDto(
    val id: String,
    val title: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    /** Set by the server on pull; ignored on push. */
    val version: Long = 0,
)

@Serializable
data class PushRequest(val changes: List<SyncChangeDto>)

@Serializable
data class PushResultDto(
    val id: String,
    /** "applied", "stale", "rejected" or "invalid". */
    val status: String,
    val version: Long = 0,
    val error: String? = null,
)

@Serializable
data class PushResponse(val results: List<PushResultDto>)

@Serializable
data class PullResponse(
    val changes: List<SyncChangeDto>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
internal data class ErrorDto(val error: String)
