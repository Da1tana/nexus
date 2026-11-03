package com.nexus.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nexus.app.ui.contacts.ContactsScreen
import com.nexus.app.ui.conversation.ConversationScreen
import com.nexus.app.ui.conversation.ConversationViewModel
import com.nexus.app.ui.diagnostics.DiagnosticsScreen
import com.nexus.app.ui.messages.MessagesScreen
import com.nexus.app.ui.search.SearchScreen
import com.nexus.app.ui.settings.SettingsScreen
import com.nexus.app.ui.settings.SourcesScreen

object Routes {
    const val MESSAGES = "messages"
    const val CONTACTS = "contacts"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val SOURCES = "settings/sources"
    const val DIAGNOSTICS = "settings/diagnostics"
    const val CONVERSATION = "conversation/{${ConversationViewModel.ARG_CONVERSATION_ID}}"

    fun conversation(id: Long) = "conversation/$id"
}

private data class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val topDestinations = listOf(
    TopDestination(Routes.MESSAGES, "Сообщения", Icons.Outlined.Forum, Icons.Filled.Forum),
    TopDestination(Routes.SEARCH, "Поиск", Icons.Outlined.Search, Icons.Filled.Search),
    TopDestination(Routes.CONTACTS, "Контакты", Icons.Outlined.People, Icons.Filled.People),
    TopDestination(Routes.SETTINGS, "Настройки", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun NexusNavHost(unreadCount: Int, navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = topDestinations.any { it.route == currentRoute }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topDestinations.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                val icon = if (selected) destination.selectedIcon else destination.icon
                                if (destination.route == Routes.MESSAGES && unreadCount > 0) {
                                    BadgedBox(badge = { Badge { Text(if (unreadCount > 99) "99+" else unreadCount.toString()) } }) {
                                        Icon(icon, contentDescription = destination.label)
                                    }
                                } else {
                                    Icon(icon, contentDescription = destination.label)
                                }
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.MESSAGES,
            modifier = Modifier.fillMaxSize().padding(padding),
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
        ) {
            val openConversation: (Long) -> Unit = { navController.navigate(Routes.conversation(it)) }

            composable(Routes.MESSAGES) { MessagesScreen(onOpenConversation = openConversation) }
            composable(Routes.CONTACTS) { ContactsScreen() }
            composable(Routes.SEARCH) { SearchScreen(onOpenConversation = openConversation) }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenSources = { navController.navigate(Routes.SOURCES) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                )
            }
            composable(Routes.SOURCES) { SourcesScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { navController.popBackStack() }) }
            composable(
                Routes.CONVERSATION,
                arguments = listOf(navArgument(ConversationViewModel.ARG_CONVERSATION_ID) { type = NavType.LongType }),
            ) {
                ConversationScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
