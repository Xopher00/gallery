// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.customtasks.mobileactions

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import com.google.ai.edge.gallery.R
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId

private const val TAG = "AGMobileActionExecutor"

// No ViewModel/Hilt dependency: both the UI ViewModel and AgentHandler's headless path call in.
object MobileActionExecutor {

  fun performAction(action: Action, context: Context): String {
    return when (action) {
      is FlashlightOnAction -> setFlashlight(context = context, isEnabled = true)
      is FlashlightOffAction -> setFlashlight(context = context, isEnabled = false)
      is CreateContactAction ->
        createContact(
          context = context,
          firstName = action.firstName,
          lastName = action.lastName,
          phoneNumber = action.phoneNumber,
          email = action.email,
        )
      is SendEmailAction ->
        sendEmail(context = context, to = action.to, subject = action.subject, body = action.body)
      is ShowLocationOnMap -> showLocationOnMap(context = context, location = action.location)
      is OpenWifiSettingsAction -> openWifiSettings(context = context)
      is CreateCalendarEventAction ->
        createCalendarEvent(context = context, datetime = action.datetime, title = action.title)
      is SetAlarmAction ->
        setAlarm(context = context, hour = action.hour, minute = action.minute, label = action.label)
      is SetTimerAction ->
        setTimer(context = context, lengthSeconds = action.lengthSeconds, label = action.label)
      is DialNumberAction -> dialNumber(context = context, phoneNumber = action.phoneNumber)
      is SendSmsAction ->
        sendSms(context = context, phoneNumber = action.phoneNumber, message = action.message)
      is OpenUrlAction -> openUrl(context = context, url = action.url)
      is OpenBluetoothSettingsAction -> openBluetoothSettings(context = context)
      is OpenSoundSettingsAction -> openSoundSettings(context = context)
      else -> ""
    }
  }

  fun setFlashlight(context: Context, isEnabled: Boolean): String {
    val cameraManager: CameraManager =
      context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    var cameraId: String? = null

    try {
      for (id in cameraManager.cameraIdList) {
        val characteristics = cameraManager.getCameraCharacteristics(id)
        val isFlashAvailable =
          characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        if (isFlashAvailable) {
          cameraId = id
          break
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set flashlight", e)
      return e.message ?: context.getString(R.string.unknown_error)
    }

    cameraId?.let { id ->
      try {
        cameraManager.setTorchMode(id, isEnabled)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to set flashlight", e)
        return e.message ?: context.getString(R.string.unknown_error)
      }
    }

    return ""
  }

  private fun createContact(
    context: Context,
    firstName: String,
    lastName: String,
    phoneNumber: String,
    email: String,
  ): String {
    val intent =
      Intent(ContactsContract.Intents.Insert.ACTION)
        .apply { type = ContactsContract.RawContacts.CONTENT_TYPE }
        .apply {
          putExtra(ContactsContract.Intents.Insert.NAME, "$firstName $lastName")
          putExtra(ContactsContract.Intents.Insert.EMAIL, email)
          putExtra(
            ContactsContract.Intents.Insert.EMAIL_TYPE,
            ContactsContract.CommonDataKinds.Email.TYPE_WORK,
          )
          putExtra(ContactsContract.Intents.Insert.PHONE, phoneNumber)
          putExtra(
            ContactsContract.Intents.Insert.PHONE_TYPE,
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
          )
        }

    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create contact", e)
      return e.message ?: context.getString(R.string.unknown_error)
    }

    return ""
  }

  private fun sendEmail(context: Context, to: String, subject: String, body: String): String {
    val intent =
      Intent(Intent.ACTION_SEND).apply {
        data = "mailto:".toUri()
        type = "text/plain"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
      }

    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to send email", e)
      return e.message ?: context.getString(R.string.unknown_error)
    }

    return ""
  }

  private fun showLocationOnMap(context: Context, location: String): String {
    val encodedLocation = URLEncoder.encode(location, StandardCharsets.UTF_8.toString())
    val intent = Intent(Intent.ACTION_VIEW).apply { data = "geo:0,0?q=$encodedLocation".toUri() }

    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to show location on map", e)
      return e.message ?: context.getString(R.string.unknown_error)
    }

    return ""
  }

  private fun openWifiSettings(context: Context): String {
    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to open wifi settings", e)
      return e.message ?: context.getString(R.string.unknown_error)
    }

    return ""
  }

  private fun setAlarm(context: Context, hour: Int, minute: Int, label: String): String {
    val intent =
      Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, hour)
        putExtra(AlarmClock.EXTRA_MINUTES, minute)
        if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
      }
    return try {
      context.startActivity(intent)
      ""
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set alarm", e)
      e.message ?: context.getString(R.string.unknown_error)
    }
  }

  private fun setTimer(context: Context, lengthSeconds: Int, label: String): String {
    val intent =
      Intent(AlarmClock.ACTION_SET_TIMER).apply {
        putExtra(AlarmClock.EXTRA_LENGTH, lengthSeconds)
        if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
      }
    return try {
      context.startActivity(intent)
      ""
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set timer", e)
      e.message ?: context.getString(R.string.unknown_error)
    }
  }

  private fun dialNumber(context: Context, phoneNumber: String): String {
    val intent = Intent(Intent.ACTION_DIAL).apply { data = "tel:$phoneNumber".toUri() }
    return try {
      context.startActivity(intent)
      ""
    } catch (e: Exception) {
      Log.e(TAG, "Failed to dial number", e)
      e.message ?: context.getString(R.string.unknown_error)
    }
  }

  private fun sendSms(context: Context, phoneNumber: String, message: String): String {
    val intent =
      Intent(Intent.ACTION_SENDTO).apply {
        data = "smsto:$phoneNumber".toUri()
        putExtra("sms_body", message)
      }
    return try {
      context.startActivity(intent)
      ""
    } catch (e: Exception) {
      Log.e(TAG, "Failed to send SMS", e)
      e.message ?: context.getString(R.string.unknown_error)
    }
  }

  private fun openUrl(context: Context, url: String): String {
    val fullUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    val intent = Intent(Intent.ACTION_VIEW).apply { data = fullUrl.toUri() }
    return try {
      context.startActivity(intent)
      ""
    } catch (e: Exception) {
      Log.e(TAG, "Failed to open URL", e)
      e.message ?: context.getString(R.string.unknown_error)
    }
  }

  private fun openBluetoothSettings(context: Context): String {
    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    return try {
      context.startActivity(intent)
      ""
    } catch (e: Exception) {
      Log.e(TAG, "Failed to open Bluetooth settings", e)
      e.message ?: context.getString(R.string.unknown_error)
    }
  }

  private fun openSoundSettings(context: Context): String {
    val intent = Intent(Settings.ACTION_SOUND_SETTINGS)
    return try {
      context.startActivity(intent)
      ""
    } catch (e: Exception) {
      Log.e(TAG, "Failed to open sound settings", e)
      e.message ?: context.getString(R.string.unknown_error)
    }
  }

  private fun createCalendarEvent(context: Context, datetime: String, title: String): String {
    var ms = System.currentTimeMillis()
    try {
      val localDateTime = LocalDateTime.parse(datetime)
      val systemDefaultZone = ZoneId.systemDefault()
      val zonedDateTime = localDateTime.atZone(systemDefaultZone)
      ms = zonedDateTime.toInstant().toEpochMilli()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to parse date time: '$datetime'", e)
    }

    val intent =
      Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, title)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ms + 3600000)
      }
    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create calendar event", e)
      return e.message ?: context.getString(R.string.unknown_error)
    }

    return ""
  }
}
