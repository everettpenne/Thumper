package com.everettpenne.thumper.music.data.jellyfin

/** Why a request failed, in terms a person can act on. */
enum class Failure(val message: String) {
    OFFLINE("Can't reach the server. Check the address and your connection."),
    SIGNED_OUT("Your session has ended. Sign in again."),
    BAD_LOGIN("That username or password was not accepted."),
    FORBIDDEN("This account isn't allowed to do that."),
    NOT_FOUND("That isn't on the server any more."),
    NOT_JELLYFIN("That address answered, but it doesn't look like a Jellyfin server."),
    BAD_RESPONSE("The server sent something unexpected."),
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Failed(val reason: Failure, val detail: String? = null) : ApiResult<Nothing>

    val valueOrNull: T? get() = (this as? Ok)?.value
    fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Failed -> this
    }
}
