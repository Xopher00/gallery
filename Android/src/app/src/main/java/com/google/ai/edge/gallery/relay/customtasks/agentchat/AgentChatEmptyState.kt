/*
 * relay: this project's own code, not part of upstream google-ai-edge/gallery.
 *
 * Extracted from AgentChatScreen.kt so that Google's file passes this body
 * through its own `emptyStateComposable` slot instead of inlining it. Keeping
 * relay behavior in relay files behind a one-line seam is what keeps upstream
 * merges clean; see reference/2026-09-05-merge-friction-reduction.md.
 */

package com.google.ai.edge.gallery.relay.customtasks.agentchat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.buildTrackableUrlAnnotatedString

/** Agent Skills onboarding empty state, passed through `AgentChatScreen`'s `emptyStateComposable`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentChatEmptyState() {
  AnimatedVisibility(
    !WindowInsets.isImeVisible,
    enter = fadeIn(animationSpec = tween(200)),
    exit = fadeOut(animationSpec = tween(200)),
  ) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(
        modifier =
          Modifier.align(Alignment.Center)
            .padding(horizontal = 48.dp)
            .padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          stringResource(R.string.introducing),
          style = MaterialTheme.typography.headlineSmall,
        )
        Text(
          stringResource(R.string.agent_skills),
          style =
            MaterialTheme.typography.headlineLarge.copy(
              fontWeight = FontWeight.Medium,
              brush =
                Brush.linearGradient(colors = listOf(Color(0xFF85B1F8), Color(0xFF3174F1))),
            ),
          modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
        )
        Text(
          buildAnnotatedString {
            append("Use specialized, high-order reasoning by loading different skills or ")
            append(
              buildTrackableUrlAnnotatedString(
                url = "https://github.com/google-ai-edge/gallery/tree/main/skills",
                linkText = "creating your own",
              )
            )
            append(".\n\nTry tapping a sample prompt below to see Agent Skills in action!")
          },
          style =
            MaterialTheme.typography.headlineSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}
