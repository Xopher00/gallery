// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.huggingface

import com.google.ai.edge.gallery.data.SOC
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins today's vendor matching of model file names in [DeviceHardwareInfo.isCompatibleWithFile]. */
class HfModelUtilsVendorMatchTest {

  private fun device(vendor: DeviceVendor, soc: String = "test-soc") =
    DeviceHardwareInfo(vendor = vendor, rawSocName = soc)

  @Test
  fun exactAliasTokens_matchTheirVendor() {
    val mtk = "model_mtk.litertlm"
    val qcom = "model_sm8.litertlm"
    val tensor = "model_tensor.litertlm"
    assertTrue(device(DeviceVendor.MEDIATEK_DIMENSITY).isCompatibleWithFile(mtk))
    assertTrue(device(DeviceVendor.QUALCOMM_SNAPDRAGON).isCompatibleWithFile(qcom))
    assertTrue(device(DeviceVendor.GOOGLE_TENSOR).isCompatibleWithFile(tensor))
    assertFalse(device(DeviceVendor.QUALCOMM_SNAPDRAGON).isCompatibleWithFile(mtk))
    assertFalse(device(DeviceVendor.MEDIATEK_DIMENSITY).isCompatibleWithFile(qcom))
    assertFalse(device(DeviceVendor.MEDIATEK_DIMENSITY).isCompatibleWithFile(tensor))
  }

  @Test
  fun aliasPlusDigits_matchRightVendor_pinsC21a45aFix() {
    val mtk = "model_mtk6989.litertlm"
    val qcom = "model_sm8650.litertlm"
    val tensor = "model_gs201.litertlm"
    assertTrue(device(DeviceVendor.MEDIATEK_DIMENSITY).isCompatibleWithFile(mtk))
    assertTrue(device(DeviceVendor.QUALCOMM_SNAPDRAGON).isCompatibleWithFile(qcom))
    assertTrue(device(DeviceVendor.GOOGLE_TENSOR).isCompatibleWithFile(tensor))
    // Cross-vendor rejection still holds for the alias-plus-digits form.
    assertFalse(device(DeviceVendor.QUALCOMM_SNAPDRAGON).isCompatibleWithFile(mtk))
    assertFalse(device(DeviceVendor.GOOGLE_TENSOR).isCompatibleWithFile(qcom))
  }

  @Test
  fun aliasPlusNonDigitSuffix_matchesNoVendor_soFileIsUniversallyCompatible() {
    // "mtkfoo" and "gs201v2" have a non-digit suffix: no vendor alias matches, so the file counts
    // as compatible with every device. This pins current behavior, not a wished-for one.
    val mtkFoo = "model_mtkfoo.litertlm"
    val gs201v2 = "model_gs201v2.litertlm"
    DeviceVendor.entries.forEach { v ->
      assertTrue(v.name, device(v).isCompatibleWithFile(mtkFoo))
      assertTrue(v.name, device(v).isCompatibleWithFile(gs201v2))
    }
  }

  @Test
  fun fileNameWithoutVendorToken_isCompatibleWithQualcommMediaTekAndGenericAndroid() {
    val plain = "gemma-3n-E2B-it.litertlm"
    assertTrue(device(DeviceVendor.QUALCOMM_SNAPDRAGON).isCompatibleWithFile(plain))
    assertTrue(device(DeviceVendor.MEDIATEK_DIMENSITY).isCompatibleWithFile(plain))
    assertTrue(device(DeviceVendor.GENERIC_ANDROID).isCompatibleWithFile(plain))
  }

  @Test
  fun mediatekTaggedFile_acceptedOnlyByMediaTek() {
    val mtk = "model_mtk6989.litertlm"
    assertFalse(device(DeviceVendor.QUALCOMM_SNAPDRAGON).isCompatibleWithFile(mtk))
    assertFalse(device(DeviceVendor.GOOGLE_TENSOR).isCompatibleWithFile(mtk))
    assertFalse(device(DeviceVendor.SAMSUNG_EXYNOS).isCompatibleWithFile(mtk))
    assertFalse(device(DeviceVendor.GENERIC_ANDROID).isCompatibleWithFile(mtk))
    assertTrue(device(DeviceVendor.MEDIATEK_DIMENSITY).isCompatibleWithFile(mtk))
  }

  @Test
  fun genericAndroidDevice_rejectsEveryVendorTaggedFile() {
    DeviceVendor.entries
      .filter { it != DeviceVendor.GENERIC_ANDROID }
      .forEach { vendor ->
        vendor.aliases.forEach { alias ->
          val file = "model_${alias}.litertlm"
          assertFalse(
            "GENERIC_ANDROID should reject ${vendor.name} file '$file'",
            device(DeviceVendor.GENERIC_ANDROID).isCompatibleWithFile(file),
          )
        }
      }
    // But it still accepts untagged files.
    assertTrue(device(DeviceVendor.GENERIC_ANDROID).isCompatibleWithFile("model.litertlm"))
  }

  @Test
  fun noAliasCollidesAcrossVendors_andSamsungModelTokenDoesNotMatchQualcomm() {
    DeviceVendor.entries
      .filter { it != DeviceVendor.GENERIC_ANDROID }
      .forEach { vendor ->
        vendor.aliases.forEach { alias ->
          val file = "model_${alias}.litertlm"
          val matched =
            DeviceVendor.entries.filter { device(it).isCompatibleWithFile(file) }
          assertTrue(
            "file '$file' should match ${vendor.name}, got ${matched.map { it.name }}",
            matched == listOf(vendor),
          )
        }
      }
    // A Samsung hardware model token in the name is not a Qualcomm alias.
    val samsung = "SM-S921B_gemma.litertlm"
    assertTrue(device(DeviceVendor.QUALCOMM_SNAPDRAGON).isCompatibleWithFile(samsung))
    assertTrue(device(DeviceVendor.GENERIC_ANDROID).isCompatibleWithFile(samsung))
  }

  @Test
  fun nonAndroidPlatformTokens_andNonLitertlmNames_areRejected() {
    val qualcomm = device(DeviceVendor.QUALCOMM_SNAPDRAGON)
    assertFalse(qualcomm.isCompatibleWithFile("model_apple.litertlm"))
    assertFalse(qualcomm.isCompatibleWithFile("model_metal.litertlm"))
    assertFalse(qualcomm.isCompatibleWithFile("model.tflite"))
    assertFalse(qualcomm.isCompatibleWithFile("model.bin"))
    assertFalse(qualcomm.isCompatibleWithFile("model-web.litertlm"))
  }
}
