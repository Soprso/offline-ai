package com.soprso.dhurandharai.ui.navigation

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.soprso.dhurandharai.models.ModelManagerViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.soprso.dhurandharai.chat.ChatViewModel
import com.soprso.dhurandharai.ui.screens.ChatScreen
import com.soprso.dhurandharai.ui.screens.GuideScreen
import com.soprso.dhurandharai.ui.screens.HistoryScreen
import com.soprso.dhurandharai.ui.screens.ModelLibraryScreen
import com.soprso.dhurandharai.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

/**
 * App-level scaffold: modal drawer wrapping the nav graph.
 * One drawer instance and one ChatViewModel shared by all destinations.
 */
@Composable
fun OfflineAiApp(navController: NavHostController) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val chatViewModel: ChatViewModel = viewModel()
    val modelManagerViewModel: ModelManagerViewModel = viewModel()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                currentRoute = currentRoute,
                onSelect = { route ->
                    scope.launch { drawerState.close() }
                    if (route == Routes.CHAT) chatViewModel.newConversation()
                    navController.navigate(route) {
                        popUpTo(Routes.CHAT) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) {
        NavHost(navController = navController, startDestination = Routes.CHAT) {
            composable(Routes.CHAT) {
                ChatScreen(
                    viewModel = chatViewModel,
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                    },
                    onOpenModels = {
                        navController.navigate(Routes.MODELS) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    viewModel = chatViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenConversation = { id ->
                        chatViewModel.openConversation(id)
                        navController.popBackStack()
                    },
                )
            }
            composable(Routes.MODELS) {
                ModelLibraryScreen(
                    viewModel = modelManagerViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenGuide = { navController.navigate(Routes.GUIDE) },
                )
            }
            composable(Routes.GUIDE) {
                GuideScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
