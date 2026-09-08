// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.tween
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.google.ai.edge.gallery.ui.discovery.DiscoveryScreen
import com.google.ai.edge.gallery.ui.home.ChatHistoryScreen
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.server.ServerScreen

// Route constants for the destinations this file registers.
internal const val ROUTE_SERVER = "api_server"
internal const val ROUTE_CHAT_HISTORY = "chat_history"

private const val TRANSITION_DURATION_MS = 500
private val TRANSITION_EASING = EaseOutExpo
private const val TRANSITION_ENTER_DELAY_MS = 100

/**
 * Registers the destinations this project adds (the API server control panel and the chat history
 * page) on [navController]'s nav graph, so GalleryNavGraph.kt only needs a single call site
 * rather than an inline composable() block per route.
 *
 * [disableHomeScreenAnimation] lets the chat history route clear GalleryNavGraph's
 * `enableHomeScreenAnimation` state on navigate-up, matching the other routes there.
 */
fun NavGraphBuilder.relayRoutes(
  navController: NavHostController,
  modelManagerViewModel: ModelManagerViewModel,
  disableHomeScreenAnimation: () -> Unit,
  discoveryEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition,
  discoveryExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition,
) {
  // API server control panel.
  composable(
    route = ROUTE_SERVER,
    enterTransition = {
      slideIntoContainer(
        animationSpec =
          tween(
            TRANSITION_DURATION_MS,
            easing = TRANSITION_EASING,
            delayMillis = TRANSITION_ENTER_DELAY_MS,
          ),
        towards = AnimatedContentTransitionScope.SlideDirection.Up,
      )
    },
    exitTransition = {
      slideOutOfContainer(
        animationSpec = tween(TRANSITION_DURATION_MS, easing = TRANSITION_EASING),
        towards = AnimatedContentTransitionScope.SlideDirection.Down,
      )
    },
  ) {
    ServerScreen(navigateUp = { navController.navigateUp() })
  }

  // Box: Chat history page.
  composable(
    route = ROUTE_CHAT_HISTORY,
    enterTransition = {
      slideIntoContainer(
        animationSpec =
          tween(
            TRANSITION_DURATION_MS,
            easing = TRANSITION_EASING,
            delayMillis = TRANSITION_ENTER_DELAY_MS,
          ),
        towards = AnimatedContentTransitionScope.SlideDirection.Up,
      )
    },
    exitTransition = {
      slideOutOfContainer(
        animationSpec = tween(TRANSITION_DURATION_MS, easing = TRANSITION_EASING),
        towards = AnimatedContentTransitionScope.SlideDirection.Down,
      )
    },
  ) {
    ChatHistoryScreen(
      navigateUp = {
        disableHomeScreenAnimation()
        navController.navigateUp()
      },
      navController = navController,
      modelManagerViewModel = modelManagerViewModel,
    )
  }

  // Hugging Face model discovery page.
  composable(
    route = "$ROUTE_DISCOVERY?taskId={taskId}",
    arguments =
      listOf(
        navArgument("taskId") {
          type = NavType.StringType
          nullable = true
          defaultValue = null
        }
      ),
    enterTransition = discoveryEnterTransition,
    exitTransition = discoveryExitTransition,
  ) { backStackEntry ->
    DiscoveryScreen(
      navigateUp = { navController.navigateUp() },
      onImportUrl = { encodedUrl, isImageGen ->
        navController.navigate(
          "$ROUTE_MODEL_MANAGER?importUrl=$encodedUrl&importIsImageGen=$isImageGen"
        )
      },
      taskId = backStackEntry.arguments?.getString("taskId"),
    )
  }
}
