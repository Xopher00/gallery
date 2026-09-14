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

package com.google.ai.edge.gallery.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val baseline = Typography()

val AppTypography = Typography()

val titleMediumNarrow = baseline.titleMedium.copy(letterSpacing = 0.0.sp)

val titleSmaller =
  baseline.titleSmall.copy(
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
  )

val labelSmallNarrow = baseline.labelSmall.copy(letterSpacing = 0.0.sp)

val labelSmallNarrowMedium =
  baseline.labelSmall.copy(
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.0.sp,
  )

val bodySmallNarrow = baseline.bodySmall.copy(letterSpacing = 0.0.sp)

val bodySmallMediumNarrow = baseline.bodySmall.copy(letterSpacing = 0.0.sp, fontSize = 14.sp)

val bodySmallMediumNarrowBold =
  baseline.bodySmall.copy(
    letterSpacing = 0.0.sp,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
  )

val homePageTitleStyle =
  baseline.displayMedium.copy(
    fontSize = 48.sp,
    lineHeight = 48.sp,
    letterSpacing = -1.sp,
    fontWeight = FontWeight.Medium,
  )

val bodyLargeNarrow = baseline.bodyLarge.copy(letterSpacing = 0.2.sp)
val bodyMediumMedium = baseline.bodyMedium.copy(fontWeight = FontWeight.Medium)

val headlineLargeMedium = baseline.headlineLarge.copy(fontWeight = FontWeight.Medium)

val emptyStateTitle = baseline.headlineSmall.copy(fontSize = 37.sp, lineHeight = 50.sp)
val emptyStateContent = baseline.headlineSmall.copy(fontSize = 16.sp, lineHeight = 22.sp)
