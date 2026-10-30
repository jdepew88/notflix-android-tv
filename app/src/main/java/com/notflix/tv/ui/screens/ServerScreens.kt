package com.notflix.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.notflix.tv.R
import com.notflix.tv.container
import com.notflix.tv.data.ParsedAddress
import com.notflix.tv.data.ServerAddress
import com.notflix.tv.data.Verification
import com.notflix.tv.discovery.DiscoveredServer
import com.notflix.tv.ui.components.NotflixButton
import com.notflix.tv.ui.components.Spinner
import com.notflix.tv.ui.components.TvTextField
import com.notflix.tv.ui.components.Wordmark
import com.notflix.tv.ui.theme.NotflixColors
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * "Welcome to NOTFLIX — Find your Notflix server". Searches the LAN as soon as it opens; the user
 * picks a server (nothing is connected automatically) or enters an address. [changing] is the
 * Settings / sign-in "Change server" variant, which can be cancelled without touching the current
 * connection.
 */
@Composable
fun ServerSetupScreen(
    changing: Boolean,
    searchAgain: StateFlow<Boolean>,
    onSearchAgainHandled: () -> Unit,
    onConnected: (keptSession: Boolean) -> Unit,
    onManual: () -> Unit,
    onTroubleshoot: () -> Unit,
    onCancel: () -> Unit,
) {
    val app = LocalContext.current.container
    val vm: ServerSetupViewModel = viewModel { ServerSetupViewModel(app) }
    val state by vm.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onStop() }
    val again by searchAgain.collectAsState()
    LaunchedEffect(again) {
        if (again) {
            vm.startSearch()
            onSearchAgainHandled()
        }
    }

    val searchAgain = remember { FocusRequester() }
    val firstCard = remember { FocusRequester() }
    // Until the user presses a key, focus follows the first result as it appears.
    var userMoved by remember { mutableStateOf(false) }
    val cards = listOfNotNull(state.previous) + state.servers
    LaunchedEffect(Unit) { runCatching { searchAgain.requestFocus() } }
    LaunchedEffect(cards.isNotEmpty()) { if (cards.isNotEmpty() && !userMoved) runCatching { firstCard.requestFocus() } }

    if (changing) BackHandler(onBack = onCancel)

    Column(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { userMoved = true; false }
            .padding(start = 72.dp, end = 72.dp, top = 44.dp, bottom = 28.dp),
    ) {
        Wordmark(height = 34.dp)
        Text(
            if (changing) "Change server" else "Welcome to NOTFLIX",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text("Find your Notflix server", style = MaterialTheme.typography.headlineSmall, color = NotflixColors.Ink3, modifier = Modifier.padding(top = 4.dp))

        Row(Modifier.weight(1f).padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                SearchStatus(state)
                LazyColumn(
                    Modifier.weight(1f).padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                ) {
                    items(cards, key = { it.serviceName }) { server ->
                        ServerCard(
                            server = server,
                            previous = server.serviceName == ServerSetupViewModel.PREVIOUS,
                            current = server.identity?.let { app.connection.isCurrent(it) } == true,
                            connecting = state.connecting == server.serviceName,
                            onClick = { vm.connect(server, onConnected) },
                            modifier = if (server == cards.first()) Modifier.focusRequester(firstCard) else Modifier,
                        )
                    }
                }
            }
            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NotflixButton(
                    "Search Again", onClick = vm::startSearch, icon = R.drawable.ic_restart,
                    modifier = Modifier.fillMaxWidth().focusRequester(searchAgain),
                )
                NotflixButton(
                    "Enter Server Address Manually", onClick = onManual, primary = !state.searching && cards.none { it.identity != null },
                    modifier = Modifier.fillMaxWidth(),
                )
                NotflixButton("Network Troubleshooting", onClick = onTroubleshoot, icon = R.drawable.ic_info, modifier = Modifier.fillMaxWidth())
                if (changing) NotflixButton("Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
            }
        }
        Text("This TV: ${state.network.summary}", color = NotflixColors.Ink4, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SearchStatus(state: ServerSetupState) {
    val found = state.servers.count { it.identity != null }
    when {
        !state.network.connected -> StatusText(
            "This TV isn't connected to a network",
            "Connect it to the same network as your Notflix server. The search starts again by itself once it's connected.",
        )
        state.searching -> Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner()
            Text("Searching for Notflix servers…", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 18.dp))
        }
        state.discoveryError != null && state.servers.isEmpty() -> StatusText(
            "Automatic search isn't working here",
            "${state.discoveryError} You can still connect by entering the server's address.",
        )
        found == 0 && state.servers.isEmpty() && state.searched -> StatusText(
            "No Notflix server found",
            "You can still connect by entering the server's IP address. Finding a server automatically needs it to announce itself on the network, which can fail even when its address works.",
        )
        found == 0 && state.searched -> StatusText(
            "No compatible Notflix server answered",
            "Something announced itself as Notflix but didn't pass the check. Choose it to see why, or enter the address manually.",
        )
        found > 0 -> Text(
            if (found == 1) "Found 1 Notflix server. Select it to connect." else "Found $found Notflix servers. Choose the one to use.",
            style = MaterialTheme.typography.titleLarge,
        )
        else -> Spacer(Modifier.height(1.dp))
    }
}

@Composable
private fun StatusText(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = NotflixColors.Ink3, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ServerCard(
    server: DiscoveredServer,
    previous: Boolean,
    current: Boolean,
    connecting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val identity = server.identity
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, NotflixColors.Focus), shape = RoundedCornerShape(10.dp))),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NotflixColors.Surface2,
            focusedContainerColor = NotflixColors.Surface3,
            contentColor = NotflixColors.Ink,
            focusedContentColor = NotflixColors.Ink,
        ),
    ) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    serverTitle(identity, if (previous) null else server.serviceName),
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val details = listOfNotNull(
                    server.address,
                    if (server.https) "HTTPS" else "HTTP",
                    identity?.apiVersion?.let { "API $it" },
                ).joinToString("  ·  ")
                Text(details, style = MaterialTheme.typography.bodyLarge, color = NotflixColors.Ink2, modifier = Modifier.padding(top = 2.dp))
                if (previous) Text("Used by this TV before the update. Select it to keep using it.", style = MaterialTheme.typography.bodySmall, color = NotflixColors.Ink3)
                (server.status as? DiscoveredServer.Status.Unavailable)?.let {
                    Text(it.message, style = MaterialTheme.typography.bodySmall, color = NotflixColors.BrandHover, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            StatusPill(
                when {
                    connecting -> "Connecting…" to NotflixColors.Ink3
                    current -> "Current" to NotflixColors.Success
                    else -> when (server.status) {
                        DiscoveredServer.Status.Checking -> "Checking…" to NotflixColors.Ink3
                        is DiscoveredServer.Status.Available -> "Available" to NotflixColors.Success
                        is DiscoveredServer.Status.Unavailable -> "Not available" to NotflixColors.BrandHover
                    }
                }
            )
        }
    }
}

@Composable
private fun StatusPill(status: Pair<String, Color>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(status.second))
        Text(status.first, style = MaterialTheme.typography.titleMedium, color = status.second, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Manual address entry. A bare IP/hostname shows the proposed http://…:8420 (then :4119) before connecting. */
@Composable
fun ManualServerScreen(changing: Boolean, onConnected: (keptSession: Boolean) -> Unit, onCancel: () -> Unit) {
    val app = LocalContext.current.container
    val vm: ManualServerViewModel = viewModel { ManualServerViewModel(app, if (changing) app.server.baseUrl else "") }
    val state by vm.state.collectAsState()
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    val parsed = ServerAddress.parse(state.text)
    val submit = { vm.connect(onConnected) }

    Column(Modifier.fillMaxSize().padding(horizontal = 120.dp, vertical = 48.dp), verticalArrangement = Arrangement.Center) {
        Wordmark(height = 34.dp)
        Text("Enter server address", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 24.dp))
        Text(
            "The address of the PC running Notflix: the one you'd open in a browser on your home network.",
            style = MaterialTheme.typography.bodyLarge, color = NotflixColors.Ink3, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp).width(720.dp),
        )
        Text(
            "Examples:  192.168.1.33   ·   192.168.1.33:8420   ·   http://media-pc.local:8420   ·   https://notflix.example.com",
            style = MaterialTheme.typography.bodyMedium, color = NotflixColors.Ink4, modifier = Modifier.padding(bottom = 22.dp),
        )
        TvTextField(
            value = state.text, onValueChange = vm::edit, label = "Server address", keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go, onImeAction = submit, placeholder = "IP address, hostname or URL",
            modifier = Modifier.width(640.dp).focusRequester(field),
        )
        when {
            parsed is ParsedAddress.Valid && parsed.suggested -> Text(
                if (parsed.addedPort) {
                    "Notflix will try ${parsed.candidates.joinToString(", then ")}  (http:// and the usual Notflix ports were added. Type a port or a full URL to use something else.)"
                } else {
                    "Notflix will try ${parsed.url}  (http:// was added. Type https:// in front to use a secure connection.)"
                },
                style = MaterialTheme.typography.bodyMedium, color = NotflixColors.Ink2, modifier = Modifier.padding(top = 12.dp).width(720.dp),
            )
            parsed is ParsedAddress.Valid -> Text(
                "Notflix will connect to ${parsed.url} exactly as entered.",
                style = MaterialTheme.typography.bodyMedium, color = NotflixColors.Ink2, modifier = Modifier.padding(top = 12.dp).width(720.dp),
            )
            else -> Spacer(Modifier.height(32.dp))
        }
        if (state.connecting && state.trying != null) {
            Text("Checking ${state.trying}…", style = MaterialTheme.typography.bodyMedium, color = NotflixColors.Ink3, modifier = Modifier.padding(top = 12.dp).width(720.dp))
        } else if (state.error != null) {
            Text(state.error!!, color = NotflixColors.BrandHover, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp).width(720.dp))
        }
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NotflixButton(if (state.connecting) "Checking…" else "Connect", onClick = submit, primary = true, enabled = !state.connecting)
            NotflixButton(if (changing) "Cancel" else "Back", onClick = onCancel)
        }
    }
}

/** Why a server may not be found, and what to check. Changes nothing on the TV or the network. */
@Composable
fun TroubleshootingScreen(onSearchAgain: () -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    val app = LocalContext.current.container
    val network by app.network.changes().collectAsState(initial = app.network.current())
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    val causes = listOf(
        "The Notflix server isn't running" to "Start it on the server PC and open it in a browser there to check.",
        "Windows Firewall blocks the port" to "Allow Node.js (TCP ${ServerAddress.DEFAULT_PORT} or your port, UDP 5353 for search) on private networks.",
        "It listens only on localhost" to "Turn on \"Allow other devices on my local network\" in the server's Settings.",
        "Wrong IP address or port" to "Check the server PC's IPv4 address (ipconfig) and the port it uses.",
        "The TV can't reach the server" to "Both must be on the same network, or on networks routed to each other.",
        "No mDNS announcement" to "Check \"Let TVs find this server automatically\" in the server's Settings, or enter the address.",
        "Multicast traffic is filtered" to "Some routers, mesh systems and VLANs drop mDNS between devices.",
        "Wi-Fi client isolation is on" to "Guest networks and \"AP isolation\" block devices from seeing each other.",
        "A VPN is active" to "A VPN on the TV or the PC can hide local devices. Pause it to test.",
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 40.dp)) {
        Wordmark(height = 30.dp)
        Text("Network troubleshooting", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 18.dp))
        Text(
            "Automatic search can fail even when the server's address works. You can always enter it instead.",
            style = MaterialTheme.typography.bodyLarge, color = NotflixColors.Ink3, modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "This TV: ${network.summary}${if (network.vpn) " (VPN active)" else ""}",
            style = MaterialTheme.typography.titleMedium, color = NotflixColors.Ink2, modifier = Modifier.padding(top = 10.dp, bottom = 14.dp),
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            causes.chunked(3).forEach { column ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    column.forEach { (title, help) ->
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(help, style = MaterialTheme.typography.bodyMedium, color = NotflixColors.Ink3)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NotflixButton("Search Again", onClick = onSearchAgain, icon = R.drawable.ic_restart, modifier = Modifier.focusRequester(first))
            NotflixButton("Enter IP Address", onClick = onManual, primary = true)
            NotflixButton("Back", onClick = onBack)
        }
        Text("Notflix never changes firewall or network settings.", style = MaterialTheme.typography.bodySmall, color = NotflixColors.Ink4, modifier = Modifier.padding(top = 10.dp))
    }
}

/**
 * The saved server didn't answer (or another server now answers at its address). The saved
 * choice is kept; nothing else is searched for or connected to unless the user says so.
 */
@Composable
fun ReconnectScreen(
    message: String,
    differentServer: Boolean,
    onRetry: () -> Unit,
    onChangeServer: () -> Unit,
    onTroubleshoot: () -> Unit,
    onConnected: (keptSession: Boolean) -> Unit,
) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val saved = app.connection.current
    val first = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    var extra by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 120.dp, vertical = 56.dp), verticalArrangement = Arrangement.Center) {
        Wordmark(height = 34.dp)
        Text(
            if (differentServer) "A different Notflix server answered" else "Can't connect to your Notflix server",
            style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 24.dp),
        )
        if (saved != null) {
            Text(
                "${serverTitle(saved.identity(), null)}  ·  ${saved.url.substringAfter("://")}",
                style = MaterialTheme.typography.titleLarge, color = NotflixColors.Ink2, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(message, style = MaterialTheme.typography.bodyLarge, color = NotflixColors.Ink3, modifier = Modifier.padding(top = 12.dp).width(760.dp))
        if (extra != null) Text(extra!!, style = MaterialTheme.typography.bodyLarge, color = NotflixColors.BrandHover, modifier = Modifier.padding(top = 10.dp).width(760.dp))
        Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NotflixButton("Retry", onClick = onRetry, primary = true, icon = R.drawable.ic_restart, modifier = Modifier.focusRequester(first))
            if (differentServer && saved != null) {
                NotflixButton(if (busy) "Checking…" else "Use this server (sign in again)", enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        when (val v = app.connection.verifier.verify(saved.url)) {
                            is Verification.Ok -> onConnected(app.connection.select(v.identity))
                            is Verification.Failed -> extra = v.message
                        }
                        busy = false
                    }
                })
            }
            NotflixButton("Change Server", onClick = onChangeServer)
            NotflixButton("Troubleshooting", onClick = onTroubleshoot, icon = R.drawable.ic_info)
        }
    }
}

/** Server Connection block in Settings: the selected server, its status, and how to change it. */
@Composable
fun ServerConnectionSettings(onSearch: () -> Unit, onManual: () -> Unit, firstFocus: FocusRequester? = null) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val saved = app.connection.current
    var status by remember { mutableStateOf<Pair<String, Color>?>(null) }
    var testing by remember { mutableStateOf(false) }

    if (saved == null) {
        Text("No server selected.", style = MaterialTheme.typography.titleMedium)
    } else {
        Text(serverTitle(saved.identity(), null), style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(
                saved.url,
                saved.apiVersion?.let { "API $it" },
                saved.lastConnectedAt?.let { "last connected ${android.text.format.DateUtils.getRelativeTimeSpanString(it)}" },
            ).joinToString("  ·  "),
            color = NotflixColors.Ink3, modifier = Modifier.padding(top = 4.dp),
        )
    }
    status?.let { (text, color) -> Text(text, color = color, modifier = Modifier.padding(top = 6.dp).width(760.dp)) }
    Text(
        "Accounts, profiles, catalog and playback all come from this Notflix server. Provider credentials never leave it. A different server needs its own sign-in.",
        color = NotflixColors.Ink3, modifier = Modifier.padding(top = 6.dp).width(760.dp),
    )
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        NotflixButton(if (testing) "Testing…" else "Test Connection", enabled = !testing && saved != null, modifier = firstFocus?.let { Modifier.focusRequester(it) } ?: Modifier, onClick = {
            testing = true
            scope.launch {
                status = when (val v = app.connection.verifier.verify(saved!!.url)) {
                    is Verification.Ok ->
                        if (saved.instanceId != null && v.identity.instanceId != null && saved.instanceId != v.identity.instanceId) {
                            "A different Notflix server now answers at this address." to NotflixColors.BrandHover
                        } else {
                            app.server.markConnected(v.identity)
                            "Connected: the Notflix server answered." to NotflixColors.Success
                        }
                    is Verification.Failed -> v.message to NotflixColors.BrandHover
                }
                testing = false
            }
        })
        NotflixButton("Search for Servers", onClick = onSearch, icon = R.drawable.ic_search)
        NotflixButton("Enter Address Manually", onClick = onManual)
    }
}
