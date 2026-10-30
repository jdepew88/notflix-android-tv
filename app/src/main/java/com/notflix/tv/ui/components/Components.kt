package com.notflix.tv.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.notflix.tv.R
import com.notflix.tv.data.MediaItem
import com.notflix.tv.data.NotflixApi
import com.notflix.tv.data.Profile
import com.notflix.tv.ui.theme.NotflixColors

@Composable
fun Wordmark(modifier: Modifier = Modifier, height: Dp = 28.dp) {
    Image(
        painter = painterResource(R.drawable.wordmark),
        contentDescription = "NOTFLIX",
        modifier = modifier.height(height).aspectRatio(3.78f),
    )
}

/** Brand-red primary / neutral secondary button with an optional leading icon. */
@Composable
fun NotflixButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) NotflixColors.Brand else NotflixColors.Surface3.copy(alpha = 0.85f),
            contentColor = NotflixColors.Ink,
            focusedContainerColor = NotflixColors.Ink,
            focusedContentColor = Color.Black,
        ),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        ButtonContent(icon, text)
    }
}

@Composable
private fun RowScope.ButtonContent(@DrawableRes icon: Int?, text: String) {
    if (icon != null) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
}

/**
 * Landscape (16:9) title card, like the website's Phase 4 cards: TMDB backdrop at w780, or the
 * poster on a dark tile when a title has no backdrop. Optional progress bar (Continue Watching).
 */
@Composable
fun MediaCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 248.dp,
    progress: Double? = null,
    caption: String? = null,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged { if (it.isFocused) onFocused() },
            shape = CardDefaults.shape(RoundedCornerShape(6.dp)),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, NotflixColors.Focus), shape = RoundedCornerShape(6.dp))),
            colors = CardDefaults.colors(containerColor = NotflixColors.Surface2),
        ) {
            Box(Modifier.fillMaxSize()) {
                val backdrop = NotflixApi.tmdbImage(item.backdropPath, "w780")
                if (backdrop != null) {
                    AsyncImage(model = backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Row(Modifier.fillMaxSize().background(NotflixColors.Surface3), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = NotflixApi.tmdbImage(item.posterPath, "w342"),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxHeight().aspectRatio(2f / 3f),
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                )
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NotflixColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = if (progress != null) 14.dp else 10.dp),
                )
                if (progress != null) {
                    ProgressLine(progress / 100.0, Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
        }
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = NotflixColors.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp),
            )
        }
    }
}

@Composable
fun ProgressLine(fraction: Double, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(NotflixColors.Brand))
    }
}

/** Single-line text input that works with a remote: focus shows a ring, OK opens the keyboard. */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    placeholder: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = NotflixColors.Ink3, modifier = Modifier.padding(bottom = 6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = NotflixColors.Ink, fontSize = 18.sp),
            cursorBrush = SolidColor(NotflixColors.Brand),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onAny = { onImeAction() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                // A text field keeps vertical D-pad keys for itself; on a remote they must move focus.
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        else -> false
                    }
                }
                .clip(RoundedCornerShape(8.dp))
                .background(if (focused) NotflixColors.Surface3 else NotflixColors.Surface2)
                .border(2.dp, if (focused) NotflixColors.Focus else NotflixColors.Line, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, color = NotflixColors.Ink4, style = MaterialTheme.typography.bodyLarge)
                    inner()
                }
            },
        )
    }
}

@Composable
fun ProfileAvatar(profile: Profile, api: NotflixApi, size: Dp, modifier: Modifier = Modifier) {
    val url = api.avatarUrl(profile.avatar)
    Box(
        modifier.size(size).clip(RoundedCornerShape(12.dp)).background(NotflixColors.Surface3),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = profile.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val glyph = profile.avatar.takeIf { it.isNotBlank() && ':' !in it } ?: profile.name.take(1).uppercase()
            Text(glyph, fontSize = (size.value * 0.42f).sp, color = NotflixColors.Ink)
        }
    }
}

@Composable
fun LoadingBlock(modifier: Modifier = Modifier, text: String? = null) {
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Spinner()
        if (text != null) Text(text, color = NotflixColors.Ink3, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 18.dp))
    }
}

/** Three pulsing brand-red dots (no Material dependency needed). */
@Composable
fun Spinner(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "spinner")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 180),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(Modifier.size(14.dp).clip(CircleShape).background(NotflixColors.Brand.copy(alpha = alpha)))
        }
    }
}

@Composable
fun ErrorBlock(
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    details: List<String> = emptyList(),
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxSize().padding(64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = NotflixColors.Ink)
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodyLarge, color = NotflixColors.Ink2, modifier = Modifier.padding(top = 12.dp).width(720.dp), maxLines = 4)
        }
        details.take(4).forEach {
            Text("• $it", style = MaterialTheme.typography.bodySmall, color = NotflixColors.Ink4, modifier = Modifier.padding(top = 4.dp).width(720.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), content = actions)
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = NotflixColors.Surface3) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
        color = NotflixColors.Ink,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * Scrolls only as far as needed to show the focused element (and reveals some context above it
 * when moving up), instead of the TV default that pivots every focus change to a fixed position.
 */
@OptIn(ExperimentalFoundationApi::class)
val MinimalBringIntoView: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            offset < 0f || size > containerSize -> offset - containerSize * 0.3f
            // Leave room below for the caption under a focused card (episode titles).
            else -> trailing - containerSize + containerSize * 0.18f
        }
    }
}

fun formatTime(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatRuntime(minutes: Int?): String? = minutes?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }
