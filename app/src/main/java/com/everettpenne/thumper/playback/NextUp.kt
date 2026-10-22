package com.everettpenne.thumper.playback

import com.everettpenne.thumper.data.model.Episode
import com.everettpenne.thumper.data.model.FeedContent
import com.everettpenne.thumper.data.model.Progress
import com.everettpenne.thumper.data.model.QueueEntry

/**
 * Pure decisions about what comes next or before the playing episode. Kept free of Android and of the player, so the whole
 * "what plays when this one ends" logic is one place the tests can drive.
 *
 * "Next" is defined by context: Up Next's first item when there is one, otherwise the next unplayed episode of the same show in
 * the order it was published: the one released after the episode that just played, so listening through a show moves forward in
 * time. (Shows are stored and listed newest first; walking down that list used to play the next *older* episode, which is backwards.)
 * "Previous" is the episode published just before the current one: the one you were on before it when listening through a show.
 */
object NextUp {
    /** Past this point, "previous" restarts the current episode rather than stepping back (the podcast-app convention). */
    const val RESTART_AFTER_MS = 10_000L

    /**
     * The episode to play after [currentId]. Up Next wins when it is not empty; otherwise the next unplayed episode of the same
     * podcast published after it (skipping played ones). Null when there is nothing newer to play, so playback stops cleanly at the
     * show's latest episode.
     */
    fun next(currentId: String?, currentFeedId: String?, queue: List<QueueEntry>, contents: Map<String, FeedContent>, progress: Map<String, Progress>): QueueEntry? {
        queue.firstOrNull { it.episode.id != currentId }?.let { return it }
        val content = contents[currentFeedId] ?: return null
        val episodes = oldestFirst(content.episodes)
        val index = episodes.indexOfFirst { it.id == currentId }
        if (index < 0) return null
        for (i in index + 1 until episodes.size) {
            val episode = episodes[i]
            if (progress[episode.id]?.played != true) return QueueEntry(episode, content.feed)
        }
        return null
    }

    /**
     * The episode published just before [currentId]. Null when the current episode is the show's oldest (or is not part of a
     * subscribed feed), so the caller restarts it instead. Unlike [next], a played episode is not skipped: "previous" is simply where
     * you were.
     */
    fun previous(currentId: String?, currentFeedId: String?, contents: Map<String, FeedContent>): QueueEntry? {
        val content = contents[currentFeedId] ?: return null
        val episodes = oldestFirst(content.episodes)
        val index = episodes.indexOfFirst { it.id == currentId }
        if (index <= 0) return null
        return QueueEntry(episodes[index - 1], content.feed)
    }

    /**
     * A show's episodes in the order they came out. By publication date when every episode has one; a feed missing dates is taken to
     * list its episodes newest first (as nearly all do, and as Thumper stores them), so its order is simply reversed. Episodes released
     * at the same moment keep the feed's relative order.
     */
    fun oldestFirst(episodes: List<Episode>): List<Episode> {
        val reversed = episodes.asReversed()
        return if (episodes.all { it.publishedAt > 0 }) reversed.sortedBy { it.publishedAt } else reversed.toList()
    }
}
