package com.notflix.tv.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.notflix.tv.container
import com.notflix.tv.data.ApiException
import com.notflix.tv.data.NotflixApi
import com.notflix.tv.data.Profile
import com.notflix.tv.data.SavedServerCheck
import com.notflix.tv.data.SelectProfileResult
import com.notflix.tv.ui.components.ErrorBlock
import com.notflix.tv.ui.components.LoadingBlock
import com.notflix.tv.ui.components.NotflixButton
import com.notflix.tv.ui.components.ProfileAvatar
import com.notflix.tv.ui.components.TvTextField
import com.notflix.tv.ui.components.Wordmark
import com.notflix.tv.ui.theme.NotflixColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Decides where to start: the connection wizard (no server yet), sign-in, the profile picker, or
 * the reconnect screen. The saved server is verified again first, but never replaced by another one.
 */
@Composable
fun BootScreen(onNoServer: () -> Unit, onSignedIn: () -> Unit, onSignedOut: () -> Unit, onServerUnreachable: (String, Boolean) -> Unit) {
    val app = LocalContext.current.container
    LaunchedEffect(Unit) {
        when (val check = app.connection.checkSaved()) {
            SavedServerCheck.NoneSelected -> onNoServer()
            is SavedServerCheck.Failed -> onServerUnreachable(check.message, false)
            is SavedServerCheck.DifferentServer -> onServerUnreachable(
                "The server answering at ${check.server.url.substringAfter("://")} isn't the one this TV was set up with. Its accounts and profiles are separate, so using it means signing in again.",
                true,
            )
            is SavedServerCheck.Ok -> try {
                val me = app.user.refreshMe()
                if (me?.user != null) onSignedIn() else onSignedOut()
            } catch (e: IOException) {
                onServerUnreachable("The server answered, but then a request failed (${e.message ?: e.javaClass.simpleName}). Try again in a moment.", false)
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark(height = 56.dp)
            Spacer(Modifier.height(28.dp))
            LoadingBlock(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun LoginScreen(onSignedIn: () -> Unit, onChangeServer: () -> Unit) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val backdrops by produceState(emptyList<String>()) { value = app.api.backdrops().mapNotNull { NotflixApi.tmdbImage(it.path, "w1280") } }
    var backdropIndex by remember { mutableStateOf(0) }
    LaunchedEffect(backdrops) {
        while (backdrops.size > 1) {
            delay(9_000)
            backdropIndex = (backdropIndex + 1) % backdrops.size
        }
    }
    LaunchedEffect(Unit) { nameFocus.requestFocus() }

    fun submit() {
        if (busy) return
        if (name.isBlank() || password.isEmpty()) {
            error = "Enter your username and password."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                app.api.login(name.trim(), password)
                app.user.refreshMe()
                onSignedIn()
            } catch (e: ApiException) {
                error = e.message
            } catch (e: IOException) {
                error = "Can't reach the Notflix server at ${app.server.baseUrl}."
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        backdrops.getOrNull(backdropIndex)?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.25f)))))
        Column(Modifier.fillMaxSize().padding(start = 96.dp, top = 56.dp, bottom = 40.dp)) {
            Wordmark(height = 34.dp)
            Spacer(Modifier.weight(1f))
            Text("Sign in", style = MaterialTheme.typography.displaySmall)
            Text("Use your Notflix account — the same one as on the website.", color = NotflixColors.Ink3, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp, bottom = 28.dp))
            TvTextField(
                value = name, onValueChange = { name = it }, label = "Username", imeAction = ImeAction.Next,
                onImeAction = { passwordFocus.requestFocus() }, modifier = Modifier.width(440.dp).focusRequester(nameFocus),
            )
            Spacer(Modifier.height(18.dp))
            TvTextField(
                value = password, onValueChange = { password = it }, label = "Password", password = true, imeAction = ImeAction.Done,
                onImeAction = ::submit, modifier = Modifier.width(440.dp).focusRequester(passwordFocus),
            )
            if (error != null) Text(error!!, color = NotflixColors.BrandHover, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 14.dp).width(440.dp))
            NotflixButton(if (busy) "Signing in…" else "Sign in", onClick = ::submit, primary = true, enabled = !busy, modifier = Modifier.padding(top = 24.dp))
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Server: ${app.connection.current?.let { serverTitle(it.identity(), null) + " · " + it.url } ?: "none"}", color = NotflixColors.Ink4, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(16.dp))
                NotflixButton("Change server", onClick = onChangeServer)
            }
        }
    }
}

/** "Who's watching?" — the account's profiles; PIN-locked ones ask for their 4-digit PIN. */
@Composable
fun ProfilesScreen(onSelected: () -> Unit, onSignedOut: () -> Unit) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var profiles by remember { mutableStateOf(emptyList<Profile>()) }
    var pinFor by remember { mutableStateOf<Profile?>(null) }
    var pinMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            val me = app.user.refreshMe()
            if (me?.user == null) onSignedOut() else profiles = me.state?.profiles ?: emptyList()
        } catch (e: IOException) {
            loadError = "Can't reach the Notflix server."
        }
        loading = false
    }

    fun select(profile: Profile, pin: String? = null) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                when (val r = app.api.selectProfile(profile.id, pin)) {
                    is SelectProfileResult.Selected -> {
                        app.user.applyState(r.state)
                        pinFor = null
                        onSelected()
                    }
                    is SelectProfileResult.PinRequired -> {
                        pinFor = profile
                        pinMessage = if (pin != null) r.message else null
                    }
                }
            } catch (e: ApiException) {
                if (pinFor != null) pinMessage = e.message else loadError = e.message
            } catch (e: IOException) {
                loadError = "Can't reach the Notflix server."
            } finally {
                busy = false
            }
        }
    }

    when {
        loading -> LoadingBlock()
        loadError != null && profiles.isEmpty() -> ErrorBlock("Something went wrong", loadError) {
            NotflixButton("Sign out", onClick = { scope.launch { app.api.logout(); app.user.clear(); onSignedOut() } })
        }
        pinFor != null -> PinPad(
            profile = pinFor!!,
            api = app.api,
            message = pinMessage,
            busy = busy,
            onSubmit = { select(pinFor!!, it) },
            onCancel = { pinFor = null; pinMessage = null },
        )
        else -> Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark(height = 30.dp, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.weight(1f))
            Text("Who's watching?", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(40.dp))
            if (profiles.isEmpty()) {
                Text("This account has no profiles yet. Create one on the Notflix website.", color = NotflixColors.Ink3, style = MaterialTheme.typography.bodyLarge)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(32.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp)) {
                items(profiles, key = { it.id }) { profile ->
                    val isFirst = profile == profiles.first()
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            onClick = { select(profile) },
                            modifier = if (isFirst) Modifier.focusRequester(firstFocus) else Modifier,
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(4.dp, NotflixColors.Focus), shape = RoundedCornerShape(14.dp))),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                        ) {
                            ProfileAvatar(profile, app.api, 148.dp)
                        }
                        Text(profile.name, style = MaterialTheme.typography.titleLarge, color = NotflixColors.Ink2, modifier = Modifier.padding(top = 14.dp))
                        val tag = when {
                            profile.hasPin -> "🔒 PIN"
                            profile.isKids -> "Kids"
                            else -> null
                        }
                        if (tag != null) Text(tag, style = MaterialTheme.typography.bodySmall, color = NotflixColors.Ink4)
                    }
                }
            }
            LaunchedEffect(profiles) { if (profiles.isNotEmpty()) runCatching { firstFocus.requestFocus() } }
            Spacer(Modifier.weight(1f))
            Text("Add or edit profiles on the Notflix website.", color = NotflixColors.Ink4, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PinPad(profile: Profile, api: NotflixApi, message: String?, busy: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember(profile.id, message) { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(profile.id) { first.requestFocus() }
    fun press(d: String) {
        if (busy || pin.length >= 4) return
        pin += d
        if (pin.length == 4) onSubmit(pin)
    }
    Row(
        Modifier.fillMaxSize().padding(64.dp).onKeyEvent { e ->
            // Number keys on the remote type the PIN directly.
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            val digit = when (e.key) {
                Key.Zero -> "0"; Key.One -> "1"; Key.Two -> "2"; Key.Three -> "3"; Key.Four -> "4"
                Key.Five -> "5"; Key.Six -> "6"; Key.Seven -> "7"; Key.Eight -> "8"; Key.Nine -> "9"
                else -> null
            }
            if (digit != null) { press(digit); true } else false
        },
        horizontalArrangement = Arrangement.spacedBy(96.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ProfileAvatar(profile, api, 132.dp)
            Text(profile.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 14.dp))
            Text("Enter the profile PIN", color = NotflixColors.Ink3, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier.size(44.dp).background(NotflixColors.Surface2, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (i < pin.length) "●" else "", fontSize = 22.sp) }
                }
            }
            if (message != null) Text(message, color = NotflixColors.BrandHover, modifier = Modifier.padding(top = 14.dp).width(320.dp), textAlign = TextAlign.Center)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✕")).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEachIndexed { c, label ->
                        Surface(
                            onClick = {
                                when (label) {
                                    "⌫" -> pin = pin.dropLast(1)
                                    "✕" -> onCancel()
                                    else -> press(label)
                                }
                            },
                            modifier = Modifier.size(72.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = NotflixColors.Surface2, focusedContainerColor = NotflixColors.Ink,
                                contentColor = NotflixColors.Ink, focusedContentColor = Color.Black,
                            ),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label, fontSize = 24.sp) }
                        }
                    }
                }
            }
        }
    }
    androidx.activity.compose.BackHandler(onBack = onCancel)
}

