package com.everettpenne.thumper.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableSharedFlow

/** A tap on the tab that is already showing. The visible list listens and goes back to its top, as iOS's tab bar does. */
object TabReselect {
    val events = MutableSharedFlow<Tab>(extraBufferCapacity = 1)
}

/** Scrolls [state] to the top when [tab] is tapped again while it is showing. */
@Composable
fun ScrollToTopOnReselect(tab: Tab, state: LazyListState) {
    LaunchedEffect(tab, state) { TabReselect.events.collect { if (it == tab) state.animateScrollToItem(0) } }
}
