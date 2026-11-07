package dev.sushnt.journal.api

/** Why a call failed, in terms the screens can act on. */
sealed interface ApiError {
    /** No connection, timeout, or the server is unreachable. Worth retrying later. */
    data class Network(val message: String) : ApiError

    /** The saved login no longer works. The user must sign in again. */
    data object SessionExpired : ApiError

    /** The server answered with an error, e.g. 409 "email already registered". */
    data class Server(val status: Int, val message: String) : ApiError
}

/** Every API call returns one of these instead of throwing, so screens must handle failure. */
sealed interface ApiResult<out T> {
    data class Ok<out T>(val value: T) : ApiResult<T>
    data class Err(val error: ApiError) : ApiResult<Nothing>
}
