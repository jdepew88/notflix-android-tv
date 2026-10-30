package com.notflix.tv.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notflix.tv.AppContainer
import com.notflix.tv.data.ParsedAddress
import com.notflix.tv.data.ServerAddress
import com.notflix.tv.data.ServerIdentity
import com.notflix.tv.data.ServerVerifier
import com.notflix.tv.data.Verification
import com.notflix.tv.data.firstNotflix
import com.notflix.tv.discovery.DiscoveredServer
import com.notflix.tv.discovery.NetworkInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerSetupState(
    val searching: Boolean = false,
    /** True once at least one search has finished. */
    val searched: Boolean = false,
    val servers: List<DiscoveredServer> = emptyList(),
    /** Phase 5 upgrade: the server this TV's existing session belongs to, offered but not used. */
    val previous: DiscoveredServer? = null,
    val discoveryError: String? = null,
    val network: NetworkInfo = NetworkInfo(connected = true),
    /** serviceName of the card being connected. */
    val connecting: String? = null,
)

/** The connection wizard: LAN discovery, plus connecting to the server the user picks. */
class ServerSetupViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ServerSetupState(network = app.network.current()))
    val state: StateFlow<ServerSetupState> = _state.asStateFlow()

    private var search: Job? = null
    private var active = false

    init {
        app.connection.previousServerSuggestion()?.let { url ->
            _state.update { it.copy(previous = DiscoveredServer(PREVIOUS, url, DiscoveredServer.Status.Checking)) }
            viewModelScope.launch { recheckPrevious() }
        }
        // Network changes: stop when the connection drops, search again when one comes back.
        viewModelScope.launch {
            app.network.changes().collect { info ->
                val before = _state.value.network
                _state.update { it.copy(network = info) }
                if (!info.connected) {
                    stopSearch()
                } else if (active && (!before.connected || before.addresses != info.addresses)) {
                    startSearch()
                }
            }
        }
    }

    /** The screen is visible: search (again) unless results are already there. */
    fun onStart() {
        active = true
        if (search?.isActive != true && !_state.value.searched) startSearch()
    }

    /** The screen went to the background: don't keep the network busy. */
    fun onStop() {
        active = false
        stopSearch()
    }

    fun startSearch() {
        search?.cancel()
        if (!_state.value.network.connected) {
            _state.update { it.copy(searching = false, searched = true, servers = emptyList(), discoveryError = null) }
            return
        }
        _state.update { it.copy(searching = true, servers = emptyList(), discoveryError = null) }
        search = viewModelScope.launch {
            app.newDiscovery().search().collect { d ->
                _state.update { it.copy(searching = d.searching, searched = it.searched || !d.searching, servers = d.servers, discoveryError = d.error) }
            }
        }
        if (_state.value.previous?.status is DiscoveredServer.Status.Unavailable) viewModelScope.launch { recheckPrevious() }
    }

    private fun stopSearch() {
        if (search?.isActive == true) {
            search?.cancel()
            _state.update { it.copy(searching = false, searched = true) }
        }
    }

    private suspend fun recheckPrevious() {
        val previous = _state.value.previous ?: return
        val status = when (val v = app.connection.verifier.verify(previous.url)) {
            is Verification.Ok -> DiscoveredServer.Status.Available(v.identity)
            is Verification.Failed -> DiscoveredServer.Status.Unavailable(v.message)
        }
        _state.update { s -> s.copy(previous = s.previous?.copy(status = status)) }
    }

    /**
     * Connects to a card the user chose: verified again right now (an earlier result may be stale),
     * then made the selected server. [onConnected] gets whether the existing sign-in was kept.
     */
    fun connect(server: DiscoveredServer, onConnected: (Boolean) -> Unit) {
        if (_state.value.connecting != null) return
        _state.update { it.copy(connecting = server.serviceName) }
        viewModelScope.launch {
            val v = app.connection.verifier.verify(server.url)
            val status = when (v) {
                is Verification.Ok -> DiscoveredServer.Status.Available(v.identity)
                is Verification.Failed -> DiscoveredServer.Status.Unavailable(v.message)
            }
            _state.update { s ->
                s.copy(
                    connecting = null,
                    servers = s.servers.map { if (it.serviceName == server.serviceName) it.copy(status = status) else it },
                    previous = s.previous?.let { if (it.serviceName == server.serviceName) it.copy(status = status) else it },
                )
            }
            if (v is Verification.Ok) {
                search?.cancel()
                // Today's servers don't report a name; the one they advertise on the network is kept instead.
                val advertised = server.serviceName.takeIf { it != PREVIOUS }?.let(ServerVerifier::cleanName)
                onConnected(app.connection.select(v.identity.copy(name = v.identity.name ?: advertised)))
            }
        }
    }

    companion object {
        const val PREVIOUS = "\u0000previous"
    }
}

data class ManualEntryState(
    val text: String = "",
    val connecting: Boolean = false,
    /** The candidate being checked right now (a bare address has two: port 8420, then 4119). */
    val trying: String? = null,
    val error: String? = null,
)

/** Typing an address by hand, with the proposed URL shown before anything is contacted. */
class ManualServerViewModel(private val app: AppContainer, initial: String) : ViewModel() {
    private val _state = MutableStateFlow(ManualEntryState(text = initial))
    val state: StateFlow<ManualEntryState> = _state.asStateFlow()

    fun edit(text: String) = _state.update { it.copy(text = text, error = null) }

    fun connect(onConnected: (Boolean) -> Unit) {
        if (_state.value.connecting) return
        val parsed = ServerAddress.parse(_state.value.text)
        if (parsed is ParsedAddress.Invalid) {
            _state.update { it.copy(error = parsed.reason) }
            return
        }
        val candidates = (parsed as ParsedAddress.Valid).candidates
        _state.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            when (val v = firstNotflix(candidates, app.connection.verifier::verify) { url -> _state.update { it.copy(trying = url) } }) {
                is Verification.Failed -> _state.update { it.copy(connecting = false, trying = null, error = v.message) }
                is Verification.Ok -> {
                    _state.update { it.copy(connecting = false, trying = null) }
                    onConnected(app.connection.select(v.identity))
                }
            }
        }
    }

}

/** "NOTFLIX — Living Room": the server's own name, else its DNS-SD name, else a generic label. */
fun serverTitle(identity: ServerIdentity?, advertisedName: String?): String {
    val name = identity?.name ?: advertisedName?.takeIf { it.isNotBlank() && !it.startsWith('\u0000') }
    return if (name != null) "NOTFLIX — $name" else "Notflix server"
}
