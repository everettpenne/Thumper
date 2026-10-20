package com.everettpenne.thumper.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** What the downloads notification says, worked out from the downloads alone. Pure, so it is unit-tested. */
data class DownloadSummary(val title: String, val text: String?, val percent: Int?, val waiting: Boolean) {
    /** The few characters a Live Update shows in the status bar chip (and the launcher's island beside the camera). */
    val shortText: String get() = percent?.let { "$it%" } ?: if (waiting) "Waiting" else "…"

    companion object {
        /** The summary while anything is downloading or waiting to, or null when nothing is. */
        fun of(records: List<DownloadRecord>, state: Map<String, DownloadInfo>): DownloadSummary? {
            val going = records.filter { state[it.episode.id]?.status == DownloadStatus.ACTIVE || state[it.episode.id]?.status == DownloadStatus.PAUSED }
            if (going.isEmpty()) return null
            val infos = going.mapNotNull { state[it.episode.id] }
            val total = infos.sumOf { it.total.coerceAtLeast(0) }
            val known = infos.all { it.total > 0 }
            val percent = if (known && total > 0) (infos.sumOf { it.bytes } * 100 / total).toInt().coerceIn(0, 100) else null
            val waiting = infos.all { it.status == DownloadStatus.PAUSED }
            val title = if (going.size == 1) going[0].episode.title else "Downloading ${going.size} episodes"
            val text = if (waiting) infos.firstNotNullOfOrNull { it.problem } else if (going.size == 1) going[0].feed.title else null
            return DownloadSummary(title, text, percent, waiting)
        }
    }
}

/**
 * Episode downloads as an Android 16 Live Update: one ongoing notification with the overall progress, which the system shows as a chip in the
 * status bar (and Uno's island shows beside the camera) instead of burying it in the shade. On earlier Android it is an ordinary progress
 * notification. Android's own DownloadManager notification is hidden in favour of this one (see [Downloads.download]), so there is only one.
 */
class DownloadLiveUpdate(private val context: Context, private val downloads: Downloads, private val scope: CoroutineScope) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    @OptIn(FlowPreview::class)
    fun start() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress of episodes being saved to the phone"; setShowBadge(false)
        })
        scope.launch {
            combine(downloads.records, downloads.state) { r, s -> DownloadSummary.of(r, s) }
                .distinctUntilChanged().sample(1_000).collect { show(it) }
        }
    }

    private fun show(summary: DownloadSummary?) {
        if (summary == null) { manager.cancel(ID); return }
        val open = PendingIntent.getActivity(context, 0, (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
            .setData(android.net.Uri.parse("thumper://downloads")).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(summary.title)
            .setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
        summary.text?.let { builder.setContentText(it) }
        builder.setProgress(100, summary.percent ?: 0, summary.percent == null)
        if (Build.VERSION.SDK_INT >= 36) {
            // A Live Update: asked for (the request is an extra in this SDK; later ones add a builder call for it), with the short text the
            // status bar chip shows. The system decides whether it qualifies, and the person can turn Live Updates off for an app; if it does
            // not, this is still an ordinary progress notification.
            builder.setShortCriticalText(summary.shortText)
            builder.addExtras(android.os.Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        }
        runCatching { manager.notify(ID, builder.build()) }   // without the notification permission there is simply nothing to show
    }

    companion object {
        const val CHANNEL = "downloads"
        const val ID = 4201
        /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING (Android 16): the notification asks to be promoted to a Live Update. */
        const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}
