package com.everettpenne.thumper.playback

import com.everettpenne.thumper.data.model.FeedContent
import com.everettpenne.thumper.data.model.Progress
import com.everettpenne.thumper.data.model.QueueEntry

/**
 * Pure decisions about what comes next or before the playing episode. Kept free of Android and of the player, so the whole
 * "what plays when this one ends" logic is one place the tests can drive.
 *
 * "Next" is defined by context: Up Next's first item when there is one, otherwise the next unplayed episode of the same show,
 * in the show's list order (newest first, as Listen and a show's page show it). "Previous" is the episode before the current one
 * in that same order — the one you were just on when listening through a show.
 */
object NextUp {
    /** Past this point, "previous" restarts the current episode rather than stepping back (the podcast-app convention). */
    const val RESTART_AFTER_MS = 10_000L

    /**
     * The episode to play after [currentId]. Up Next wins when it is not empty; otherwise the next unplayed episode of the same
     * podcast (skipping played ones) is chosen. Null when there is nothing next, so playback can stop cleanly.
     */
    fun next(currentId: String?, currentFeedId: String?, queue: List<QueueEntry>, contents: Map<String, FeedContent>, progress: Map<String, Progress>): QueueEntry? {
        queue.firstOrNull { it.episode.id != currentId }?.let { return it }
        val content = contents[currentFeedId] ?: return null
        val index = content.episodes.indexOfFirst { it.id == currentId }
        if (index < 0) return null
        for (i in index + 1 until content.episodes.size) {
            val episode = content.episodes[i]
            if (progress[episode.id]?.played != true) return QueueEntry(episode, content.feed)
        }
        return null
    }

    /**
     * The episode before [currentId] in its podcast's list order. Null when the current episode is the first in the list (or is not
     * part of a subscribed feed), so the caller restarts it instead. Unlike [next], a played episode is not skipped: "previous" is
     * simply where you were.
     */
    fun previous(currentId: String?, currentFeedId: String?, contents: Map<String, FeedContent>): QueueEntry? {
        val content = contents[currentFeedId] ?: return null
        val index = content.episodes.indexOfFirst { it.id == currentId }
        if (index <= 0) return null
        return QueueEntry(content.episodes[index - 1], content.feed)
    }
}
