// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0.

package com.google.ai.edge.gallery.skills

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillDestinationDirTest {

  private fun newFilesDir(): File = Files.createTempDirectory("skill-files").toFile()

  private fun assertInside(filesDir: File, dir: File) {
    val canonicalDir = dir.canonicalFile.toPath()
    val canonicalFilesDir = filesDir.canonicalFile.toPath()
    assertTrue(
      "expected $canonicalDir to be inside $canonicalFilesDir",
      canonicalDir.startsWith(canonicalFilesDir),
    )
  }

  @Test
  fun plainNameLandsInSkillsDir() {
    val filesDir = newFilesDir()
    val dir = skillDestinationDir(filesDir, "my-skill")
    assertInside(filesDir, dir)
    assertEquals(File(filesDir, "skills/my-skill").path, dir.path)
  }

  @Test
  fun spacesCollapseToDash() {
    val filesDir = newFilesDir()
    val dir = skillDestinationDir(filesDir, "my skill")
    assertInside(filesDir, dir)
    assertEquals(skillDestinationDir(filesDir, "my-skill").path, dir.path)
  }

  @Test
  fun traversalSegmentsStayInside() {
    val filesDir = newFilesDir()
    val displayName = listOf("..", "..", "..").joinToString("/")
    val sanitized = sanitizeSkillDirName(displayName)
    assertFalse("sanitized leaf must not contain '/'", sanitized.contains('/'))
    val dir = skillDestinationDir(filesDir, displayName)
    assertInside(filesDir, dir)
    val skillsDir = File(filesDir, "skills").canonicalFile.toPath()
    assertTrue(
      "expected $dir to be inside $skillsDir",
      dir.canonicalFile.toPath().startsWith(skillsDir),
    )
  }

  @Test
  fun parentDirNameFallsBack() {
    val filesDir = newFilesDir()
    val dir = skillDestinationDir(filesDir, "..")
    assertInside(filesDir, dir)
    assertEquals(File(filesDir, "skills/skill").path, dir.path)
  }

  @Test
  fun selfDirNameFallsBack() {
    val filesDir = newFilesDir()
    val dir = skillDestinationDir(filesDir, ".")
    assertInside(filesDir, dir)
    assertEquals(File(filesDir, "skills/skill").path, dir.path)
  }

  @Test
  fun emptyNameFallsBack() {
    val filesDir = newFilesDir()
    val dir = skillDestinationDir(filesDir, "")
    assertInside(filesDir, dir)
    assertEquals(File(filesDir, "skills/skill").path, dir.path)
  }

  @Test
  fun reservedCharsCollapse() {
    val filesDir = newFilesDir()
    val dir = skillDestinationDir(filesDir, "a/b:c")
    assertInside(filesDir, dir)
    assertEquals(File(filesDir, "skills/a-b-c").path, dir.path)
  }

  @Test
  fun unicodeNameCollapses() {
    val filesDir = newFilesDir()
    val dir = skillDestinationDir(filesDir, "技能")
    assertInside(filesDir, dir)
    assertEquals(File(filesDir, "skills/--").path, dir.path)
  }
}
