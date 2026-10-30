package com.notflix.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import com.notflix.tv.playback.PlayRequest
import com.notflix.tv.ui.screens.BootScreen
import com.notflix.tv.ui.screens.DetailsScreen
import com.notflix.tv.ui.screens.LoginScreen
import com.notflix.tv.ui.screens.MainScreen
import com.notflix.tv.ui.screens.PlayerScreen
import com.notflix.tv.ui.screens.ProfilesScreen
import com.notflix.tv.ui.screens.ManualServerScreen
import com.notflix.tv.ui.screens.ReconnectScreen
import com.notflix.tv.ui.screens.ServerSetupScreen
import com.notflix.tv.ui.screens.TroubleshootingScreen
import com.notflix.tv.ui.theme.NotflixColors
import kotlinx.serialization.Serializable

@Serializable object BootRoute
/** The connection wizard; [changing] = opened from sign-in or Settings (cancellable, keeps the current server). */
@Serializable data class ServerSetupRoute(val changing: Boolean = false)
@Serializable data class ManualServerRoute(val changing: Boolean = false)
@Serializable data class TroubleshootingRoute(val changing: Boolean = false)
@Serializable data class ReconnectRoute(val message: String, val differentServer: Boolean = false)

/** Set on the wizard's back-stack entry to make it search again when it is shown. */
private const val SEARCH_AGAIN = "searchAgain"
@Serializable object LoginRoute
@Serializable object ProfilesRoute
@Serializable data class MainRoute(val tab: String = Tab.Home.name)
@Serializable data class DetailsRoute(val tmdbId: Int, val media: String)

enum class Tab { Home, Movies, Shows, Search, MyList, Settings }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NotflixRoot() {
    val nav = rememberNavController()
    val view = LocalView.current
    // A keyboard left bound after a text field (sign-in, search) keeps receiving the remote's Back
    // key on Google TV, so close it whenever the screen changes.
    LaunchedEffect(nav) { nav.currentBackStackEntryFlow.collect { hideKeyboard(view) } }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    NavHost(
        nav,
        startDestination = BootRoute,
        modifier = Modifier
            .fillMaxSize()
            .background(NotflixColors.Canvas)
            // Gboard on Google TV can stay bound after a text field was used and then swallows
            // every Back press (keys reach the keyboard before the app). Take Back first whenever
            // no keyboard is on screen; with a keyboard showing, Back still closes it.
            .onInterceptKeyBeforeSoftKeyboard { e ->
                if (e.key != Key.Back || keyboardVisible(view)) return@onInterceptKeyBeforeSoftKeyboard false
                if (e.type == KeyEventType.KeyUp) backDispatcher?.onBackPressed()
                true
            },
    ) {
        composable<BootRoute> {
            BootScreen(
                onNoServer = { nav.resetTo(ServerSetupRoute()) },
                onSignedIn = { nav.resetTo(ProfilesRoute) },
                onSignedOut = { nav.resetTo(LoginRoute) },
                onServerUnreachable = { message, different -> nav.resetTo(ReconnectRoute(message, different)) },
            )
        }
        composable<ServerSetupRoute> { entry ->
            val changing = entry.toRoute<ServerSetupRoute>().changing
            ServerSetupScreen(
                changing = changing,
                searchAgain = entry.savedStateHandle.getStateFlow(SEARCH_AGAIN, false),
                onSearchAgainHandled = { entry.savedStateHandle[SEARCH_AGAIN] = false },
                onConnected = { nav.resetTo(BootRoute) },
                onManual = { nav.navigate(ManualServerRoute(changing)) },
                onTroubleshoot = { nav.navigate(TroubleshootingRoute(changing)) },
                onCancel = { nav.popBackStack() },
            )
        }
        composable<ManualServerRoute> { entry ->
            ManualServerScreen(
                changing = entry.toRoute<ManualServerRoute>().changing,
                onConnected = { nav.resetTo(BootRoute) },
                onCancel = { nav.popBackStack() },
            )
        }
        composable<TroubleshootingRoute> { entry ->
            val changing = entry.toRoute<TroubleshootingRoute>().changing
            TroubleshootingScreen(
                onSearchAgain = {
                    val wizard = nav.previousBackStackEntry?.takeIf { it.destination.hasRoute<ServerSetupRoute>() }
                    if (wizard != null) {
                        wizard.savedStateHandle[SEARCH_AGAIN] = true
                        nav.popBackStack()
                    } else {
                        nav.navigate(ServerSetupRoute(changing)) { popUpTo<TroubleshootingRoute> { inclusive = true } }
                    }
                },
                onManual = { nav.navigate(ManualServerRoute(changing)) { popUpTo<TroubleshootingRoute> { inclusive = true } } },
                onBack = { nav.popBackStack() },
            )
        }
        composable<ReconnectRoute> { entry ->
            val route = entry.toRoute<ReconnectRoute>()
            ReconnectScreen(
                message = route.message,
                differentServer = route.differentServer,
                onRetry = { nav.resetTo(BootRoute) },
                onChangeServer = { nav.navigate(ServerSetupRoute(changing = true)) },
                onTroubleshoot = { nav.navigate(TroubleshootingRoute(changing = true)) },
                onConnected = { nav.resetTo(BootRoute) },
            )
        }
        composable<LoginRoute> {
            LoginScreen(onSignedIn = { nav.resetTo(ProfilesRoute) }, onChangeServer = { nav.navigate(ServerSetupRoute(changing = true)) })
        }
        composable<ProfilesRoute> {
            ProfilesScreen(onSelected = { nav.resetTo(MainRoute()) }, onSignedOut = { nav.resetTo(LoginRoute) })
        }
        composable<MainRoute> { entry ->
            MainScreen(
                tab = Tab.valueOf(entry.toRoute<MainRoute>().tab),
                onTab = { tab -> nav.navigate(MainRoute(tab.name)) { popUpTo<MainRoute> { inclusive = true }; launchSingleTop = true } },
                onOpen = { item -> item.tmdbId?.let { nav.navigate(DetailsRoute(it, item.tmdbMedia)) } },
                onPlay = { nav.navigate(it) },
                onSwitchProfile = { nav.resetTo(ProfilesRoute) },
                onSignedOut = { nav.resetTo(LoginRoute) },
                onSearchServers = { nav.navigate(ServerSetupRoute(changing = true)) },
                onEnterServerManually = { nav.navigate(ManualServerRoute(changing = true)) },
            )
        }
        composable<DetailsRoute> { entry ->
            val route = entry.toRoute<DetailsRoute>()
            DetailsScreen(
                tmdbId = route.tmdbId,
                media = route.media,
                onPlay = { nav.navigate(it) },
                onOpen = { item -> item.tmdbId?.let { nav.navigate(DetailsRoute(it, item.tmdbMedia)) } },
                onBack = { nav.popBackStack() },
            )
        }
        composable<PlayRequest> { entry ->
            PlayerScreen(request = entry.toRoute<PlayRequest>(), onExit = { nav.popBackStack() })
        }
    }
}

private fun keyboardVisible(view: View): Boolean =
    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true

fun hideKeyboard(view: View) {
    view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
}

private fun NavHostController.resetTo(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
