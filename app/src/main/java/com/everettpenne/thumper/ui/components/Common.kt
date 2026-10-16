package com.everettpenne.thumper.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.everettpenne.thumper.data.api.ApiResult
import com.everettpenne.thumper.data.api.Failure
import com.everettpenne.thumper.ui.glass.GlassButton
import com.everettpenne.thumper.ui.glass.glassPalette
import com.everettpenne.thumper.ui.glass.glassSurface

/** Where a tap goes. Pure callbacks, so screens do not know about navigation. */
interface Navigator {
    fun openFeed(url: String)
    fun openEpisode(feedId: String, episodeId: String)
    fun openArticle(feedId: String, articleId: String)
    fun openPlayer()
    fun openSettings()
    fun addFeed(url: String? = null)
    fun openSearch()
}

/** What a screen's data is doing. A reload keeps the old value on screen until the new one arrives. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Error(val failure: Failure) : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val reload: () -> Unit)

/** Runs [block] when [key] changes (and on [Loader.reload]) and exposes its outcome. */
@Composable
fun <T> rememberLoad(key: Any?, block: suspend () -> ApiResult<T>): Loader<T> {
    var nonce by remember { mutableIntStateOf(0) }
    val latest by rememberUpdatedState(block)
    val state by produceState<Load<T>>(Load.Loading, key, nonce) {
        value = when (val r = latest()) { is ApiResult.Ok -> Load.Done(r.value); is ApiResult.Failed -> Load.Error(r.reason) }
    }
    return Loader(state) { nonce++ }
}

@Composable
fun topContentPadding(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp

@Composable
fun bottomContentPadding(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 104.dp +
    (if (com.everettpenne.thumper.playback.LocalPlayback.current.hasEpisode) 76.dp else 0.dp)          // room for the mini player above the tab bar

@Composable
fun LoadingRow() = Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) }

@Composable
fun ErrorState(failure: Failure, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(failure.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassButton("Try again", onRetry)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A glass text field: a search box, a server address, a password. [leading] is an optional icon. */
@Composable
fun GlassTextField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, leading: ImageVector? = null,
    password: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text, imeAction: ImeAction = ImeAction.Done, onSubmit: () -> Unit = {},
) {
    val ink = glassPalette().ink
    androidx.compose.foundation.text.BasicTextField(value, onValueChange, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink), cursorBrush = SolidColor(ink),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = if (password) KeyboardType.Password else keyboardType, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }, onSearch = { onSubmit() }, onGo = { onSubmit() }, onNext = { onSubmit() }),
        modifier = modifier.fillMaxWidth().height(52.dp).glassSurface(androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
            tint = ink.copy(alpha = .06f).compositeOver(glassPalette().tint), blurRadius = 6f, lensHeight = 10f, lensAmount = 20f, shadowed = false),
        decorationBox = { inner ->
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (leading != null) Icon(leading, null, tint = ink.copy(alpha = .6f), modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f)) { if (value.isEmpty()) Text(placeholder, color = ink.copy(alpha = .45f), style = MaterialTheme.typography.bodyLarge); inner() }
                if (value.isNotEmpty() && !password) Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Clear", tint = ink.copy(alpha = .6f),
                    modifier = Modifier.size(20.dp).clickable { onValueChange("") })
            }
        })
}
