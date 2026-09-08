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
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

private const val TAG = "AGMATools"

class MobileActionsTools(val onFunctionCalled: (Action) -> Unit) : ToolSet {
  @Tool(description = "Turn on flashlight")
  fun turnOnFlashlight(): Map<String, String> {
    Log.d(TAG, "turn on flashlight")

    // Call the callback with the recognized action.
    onFunctionCalled(FlashlightOnAction())

    // Return a response object to the model confirming the action.
    return mapOf("result" to "success")
  }

  @Tool(description = "Turn off flashlight")
  fun turnOffFlashlight(): Map<String, String> {
    Log.d(TAG, "turn off flashlight")

    // Call the callback with the recognized action.
    onFunctionCalled(FlashlightOffAction())

    // Return a response object to the model confirming the action.
    return mapOf("result" to "success")
  }

  @Tool(description = "Add contact")
  fun createContact(
    @ToolParam(description = "First name") firstName: String,
    @ToolParam(description = "Last name") lastName: String,
    @ToolParam(description = "Phone number") phoneNumber: String,
    @ToolParam(description = "Email") email: String,
  ): Map<String, String> {
    Log.d(
      TAG,
      "create contact. First name: '$firstName', last name: '$lastName', phone number: '$phoneNumber', email: '$email'",
    )

    onFunctionCalled(
      CreateContactAction(
        firstName = firstName,
        lastName = lastName,
        phoneNumber = phoneNumber,
        email = email,
      )
    )

    return mapOf(
      "result" to "success",
      "first_name" to firstName,
      "last_name" to lastName,
      "phone_number" to phoneNumber,
      "email" to email,
    )
  }

  @Tool(description = "Send email")
  fun sendEmail(
    @ToolParam(description = "Recipient email") to: String,
    @ToolParam(description = "Subject") subject: String,
    @ToolParam(description = "Body") body: String,
  ): Map<String, String> {
    Log.d(TAG, "send email. To: '$to', subject: '$subject', body: '$body'")

    onFunctionCalled(SendEmailAction(to = to, subject = subject, body = body))

    return mapOf("result" to "success", "to" to to, "subject" to subject, "body" to body)
  }

  @Tool(description = "Show location on map")
  fun showLocationOnMap(
    @ToolParam(description = "Place name or address") location: String
  ): Map<String, String> {
    Log.d(TAG, "Show location on map. Location: '$location'")

    onFunctionCalled(ShowLocationOnMap(location = location))

    return mapOf("result" to "success", "location" to location)
  }

  @Tool(description = "Open WiFi settings")
  fun openWifiSettings(): Map<String, String> {
    Log.d(TAG, "Open wifi settings")

    onFunctionCalled(OpenWifiSettingsAction())

    return mapOf("result" to "success")
  }

  @Tool(description = "Create calendar event")
  fun createCalendarEvent(
    @ToolParam(description = "Datetime YYYY-MM-DDTHH:MM:SS") datetime: String,
    @ToolParam(description = "Title") title: String,
  ): Map<String, String> {
    Log.d(TAG, "Create calendar event. Datetime: '$datetime', title: '$title'")

    onFunctionCalled(CreateCalendarEventAction(datetime = datetime, title = title))

    return mapOf("result" to "success", "datetime" to datetime, "title" to title)
  }

  @Tool(description = "Set alarm")
  fun setAlarm(
    @ToolParam(description = "Hour 0-23") hour: Int,
    @ToolParam(description = "Minute 0-59") minute: Int,
    @ToolParam(description = "Label") label: String,
  ): Map<String, String> {
    Log.d(TAG, "Set alarm. Hour: $hour, minute: $minute, label: '$label'")

    onFunctionCalled(SetAlarmAction(hour = hour, minute = minute, label = label))

    return mapOf("result" to "success", "hour" to hour.toString(), "minute" to minute.toString())
  }

  @Tool(description = "Set countdown timer")
  fun setTimer(
    @ToolParam(description = "Duration seconds") lengthSeconds: Int,
    @ToolParam(description = "Label") label: String,
  ): Map<String, String> {
    Log.d(TAG, "Set timer. Length: ${lengthSeconds}s, label: '$label'")

    onFunctionCalled(SetTimerAction(lengthSeconds = lengthSeconds, label = label))

    return mapOf("result" to "success", "lengthSeconds" to lengthSeconds.toString())
  }

  @Tool(description = "Dial phone number")
  fun dialNumber(
    @ToolParam(description = "Phone number") phoneNumber: String
  ): Map<String, String> {
    Log.d(TAG, "Dial number: '$phoneNumber'")

    onFunctionCalled(DialNumberAction(phoneNumber = phoneNumber))

    return mapOf("result" to "success", "phoneNumber" to phoneNumber)
  }

  @Tool(description = "Send SMS")
  fun sendSms(
    @ToolParam(description = "Phone number") phoneNumber: String,
    @ToolParam(description = "Message") message: String,
  ): Map<String, String> {
    Log.d(TAG, "Send SMS. To: '$phoneNumber', message: '$message'")

    onFunctionCalled(SendSmsAction(phoneNumber = phoneNumber, message = message))

    return mapOf("result" to "success", "phoneNumber" to phoneNumber, "message" to message)
  }

  @Tool(description = "Open URL in browser")
  fun openUrl(
    @ToolParam(
      description = "URL with scheme"
    )
    url: String
  ): Map<String, String> {
    Log.d(TAG, "Open URL: '$url'")

    onFunctionCalled(OpenUrlAction(url = url))

    return mapOf("result" to "success", "url" to url)
  }

  @Tool(description = "Open Bluetooth settings")
  fun openBluetoothSettings(): Map<String, String> {
    Log.d(TAG, "Open Bluetooth settings")

    onFunctionCalled(OpenBluetoothSettingsAction())

    return mapOf("result" to "success")
  }

  @Tool(description = "Open sound settings")
  fun openSoundSettings(): Map<String, String> {
    Log.d(TAG, "Open sound settings")

    onFunctionCalled(OpenSoundSettingsAction())

    return mapOf("result" to "success")
  }
}
