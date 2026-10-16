package com.everettpenne.thumper.data.api

/** Why a request failed, in terms a person can act on. */
enum class Failure(val message: String) {
    OFFLINE("Can't reach the server. Check your connection."),
    NOT_FOUND("That address doesn't exist any more."),
    NOT_A_FEED("That address answered, but it isn't an RSS or Atom feed."),
    TOO_LARGE("That feed is too large to read."),
    KEYS_REJECTED("Podcast Index didn't accept your key and secret. Check them in Settings."),
    NEEDS_KEYS("Podcast Index needs your key and secret. Add them in Settings, or switch to Apple's search."),
    BAD_RESPONSE("The server sent something unexpected."),
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Failed(val reason: Failure, val detail: String? = null) : ApiResult<Nothing>

    val valueOrNull: T? get() = (this as? Ok)?.value
    fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) { is Ok -> Ok(transform(value)); is Failed -> this }
}
