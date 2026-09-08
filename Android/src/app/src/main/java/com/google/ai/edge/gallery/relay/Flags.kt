/*
 * relay: this project's own code, not part of upstream google-ai-edge/gallery.
 */

package com.google.ai.edge.gallery.relay

/**
 * Named on/off switches for Google-owned features this project disables in its UI.
 *
 * Gate a Google feature with `if (Flags.x) { ... }` around Google's own code, rather than
 * deleting the code or wrapping it in a `/* ... */` comment. Google's text then stays byte-for-byte
 * present in its own file: a future upstream edit *inside* that region applies and merges cleanly,
 * where a deletion or a comment-out would conflict on every such edit. See
 * `reference/2026-09-05-merge-friction-reduction.md` §2.1/§4c for the rationale and the cases this
 * was measured against.
 *
 * Start small and grow this object as each deletion is converted to a flag. Known candidates not
 * yet converted: the TinyGarden feature removal in `ModelManagerViewModel.kt`, and the
 * Terms-of-Service section removed from `SettingsDialog.kt`.
 */
object Flags {
  /** Google's Gemma-4 promo banner in `GlobalModelManager.kt`. This project disables it. */
  const val SHOW_GEMMA4_PROMO_BANNER: Boolean = false

  /**
   * Google's low-memory warning before a model pick/download (`ModelPicker.kt`,
   * `DownloadAndTryButton.kt`). Previously deleted outright; restored and gated here per
   * `reference/2026-09-05-merge-friction-reduction.md` §2.1 (item 12/15) so Google's code stays
   * in place. Accuracy rests on each model's allowlist `minDeviceMemoryInGb`, which we don't set.
   */
  const val MEMORY_WARNING_ENABLED: Boolean = true
}
