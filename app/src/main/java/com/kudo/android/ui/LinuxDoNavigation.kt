package com.kudo.android.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController

internal fun NavHostController.openWebPage(value: String, invalid: () -> Unit = {}, nativeConnect: Boolean = true,
    miniApp: ((String) -> Unit)? = null) {
    val url = LinuxDoLink.webUrl(value) ?: return invalid()
    if (nativeConnect && LinuxDoLink.parseContent(url) == LinuxDoDestination.Trust) {
        return openLinuxDoDestination(LinuxDoDestination.Trust, {})
    }
    if (LinuxDoLink.parseContent(url) == LinuxDoDestination.Apps) {
        return openLinuxDoDestination(LinuxDoDestination.Apps, {})
    }
    if (miniApp != null && LinuxDoLink.parseContent(url) is LinuxDoDestination.MiniApp) return miniApp(url)
    navigate("web?url=${Uri.encode(url)}")
}

internal fun NavHostController.openContentLink(value: String, home: () -> Unit, invalid: () -> Unit = {},
    miniApp: ((String) -> Unit)? = null) {
    val target = LinuxDoLink.parseContent(value)
    if (target != null) openLinuxDoDestination(target, home, miniApp) else openWebPage(value, invalid, miniApp = miniApp)
}

internal fun NavHostController.openLinuxDoDestination(target: LinuxDoDestination, home: () -> Unit, miniApp: ((String) -> Unit)? = null) {
    when (target) {
        // Each linked topic is a separate reading context; keep earlier topics on the back stack.
        is LinuxDoDestination.Topic -> navigate("topic/${target.id}?post=${target.number ?: 0}")
        is LinuxDoDestination.User -> navigate("user/${Uri.encode(target.username)}") { launchSingleTop = true }
        is LinuxDoDestination.Category -> navigate("category/${target.id}") { launchSingleTop = true }
        is LinuxDoDestination.Tag -> navigate("tag/${Uri.encode(target.slug)}?id=${target.id ?: 0}") { launchSingleTop = true }
        LinuxDoDestination.Unavailable -> navigate("unavailable") { launchSingleTop = true }
        LinuxDoDestination.Trust -> navigate("trust") { launchSingleTop = true }
        LinuxDoDestination.Apps -> navigate("apps") { launchSingleTop = true }
        is LinuxDoDestination.MiniApp -> if (miniApp != null) miniApp(target.url) else navigate("web?url=${Uri.encode(target.url)}")
        LinuxDoDestination.Home -> { home(); popBackStack("root", false) }
    }
}

@Composable
internal fun IncomingLinuxDoLink(nav: NavHostController, url: String?, sequence: Long, home: () -> Unit,
    ready: Boolean = true, miniApp: ((String) -> Unit)? = null, consume: () -> Unit) {
    LaunchedEffect(url, sequence, ready) {
        if (!ready) return@LaunchedEffect
        url?.let { value ->
            LinuxDoLink.parse(value)?.let { nav.openLinuxDoDestination(it, home, miniApp) }
            consume()
        }
    }
}
