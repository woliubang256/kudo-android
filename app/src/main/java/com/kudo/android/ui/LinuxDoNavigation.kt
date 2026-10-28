package com.kudo.android.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController

internal fun NavHostController.openWebPage(value: String, invalid: () -> Unit = {}, nativeConnect: Boolean = true) {
    val url = LinuxDoLink.webUrl(value) ?: return invalid()
    if (nativeConnect && LinuxDoLink.parseContent(url) == LinuxDoDestination.Trust) {
        return openLinuxDoDestination(LinuxDoDestination.Trust, {})
    }
    navigate("web?url=${Uri.encode(url)}")
}

internal fun NavHostController.openContentLink(value: String, home: () -> Unit, invalid: () -> Unit = {}) {
    val target = LinuxDoLink.parseContent(value)
    if (target != null) openLinuxDoDestination(target, home) else openWebPage(value, invalid)
}

internal fun NavHostController.openLinuxDoDestination(target: LinuxDoDestination, home: () -> Unit) {
    when (target) {
        // Each linked topic is a separate reading context; keep earlier topics on the back stack.
        is LinuxDoDestination.Topic -> navigate("topic/${target.id}?post=${target.number ?: 0}")
        is LinuxDoDestination.User -> navigate("user/${Uri.encode(target.username)}") { launchSingleTop = true }
        is LinuxDoDestination.Category -> navigate("category/${target.id}") { launchSingleTop = true }
        is LinuxDoDestination.Tag -> navigate("tag/${Uri.encode(target.slug)}?id=${target.id ?: 0}") { launchSingleTop = true }
        LinuxDoDestination.Unavailable -> navigate("unavailable") { launchSingleTop = true }
        LinuxDoDestination.Trust -> navigate("trust") { launchSingleTop = true }
        LinuxDoDestination.Home -> { home(); popBackStack("root", false) }
    }
}

@Composable
internal fun IncomingLinuxDoLink(nav: NavHostController, url: String?, sequence: Long, home: () -> Unit, consume: () -> Unit) {
    LaunchedEffect(url, sequence) {
        url?.let { value ->
            LinuxDoLink.parse(value)?.let { nav.openLinuxDoDestination(it, home) }
            consume()
        }
    }
}
