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
data class GoogleSignInRequest(@SerialName("id_token") val idToken: String)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ResetPasswordRequest(val email: String, val code: String, val password: String)

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

// ---------- photos ----------

@Serializable
data class AttachmentDto(
    val id: String,
    @SerialName("entry_id") val entryId: String,
    @SerialName("content_type") val contentType: String = "image/jpeg",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val position: Int = 0,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val version: Long = 0,
)

@Serializable
data class CreateAttachmentRequest(
    val id: String,
    @SerialName("entry_id") val entryId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val position: Int,
)

@Serializable
data class CreateAttachmentResponse(
    val attachment: AttachmentDto,
    val uploaded: Boolean,
    @SerialName("upload_url") val uploadUrl: String? = null,
)

@Serializable
data class AttachmentUrlResponse(val url: String)

@Serializable
data class AttachmentPullResponse(
    val attachments: List<AttachmentDto>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
)
