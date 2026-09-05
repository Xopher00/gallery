/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.tween
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.google.ai.edge.gallery.ui.server.ServerScreen

// Route constants for the two destinations this file registers.
internal const val ROUTE_SERVER = "api_server"

private const val TRANSITION_DURATION_MS = 500
private val TRANSITION_EASING = EaseOutExpo
private const val TRANSITION_ENTER_DELAY_MS = 100

/**
 * Registers the destinations this fork adds (currently the API server control panel) on
 * [navController]'s nav graph, so GalleryNavGraph.kt only needs a single call site rather than
 * an inline composable() block.
 *
 * Chat history is NOT registered here: the merged base already routes ROUTE_CHAT_HISTORY to its
 * own ChatHistoryScreen in GalleryNavGraph.kt.
 */
fun NavGraphBuilder.forkExtraRoutes(
  navController: NavHostController,
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

}
