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

/**
 * What actually happened when a `@Tool` method handed its [Action] to
 * [MobileActionsTools.onFunctionCalled]. Every `@Tool` method used to return an unconditional
 * "success" map to the model regardless of whether the action was permitted or whether it
 * actually happened. This is the outcome channel that lets a caller of [MobileActionsTools] --
 * the allowlist-gated consumer in openai/handlers/AgentHandler.kt, or the ungated in-app consumer
 * in MobileActionsTask.kt -- report the truth back through the map each `@Tool` method returns.
 */
sealed class ToolOutcome {
  /** The action was permitted and actually happened. */
  object Success : ToolOutcome()

  /**
   * The caller refused to perform this action. [toolName] names the refused tool so the model can
   * reason about which call failed; [reason] is a short, human-readable phrase (default covers the
   * common "not on the allowlist" case). Deliberately does not enumerate which tools ARE allowed --
   * an agent must not be able to learn the enabled-tool configuration by probing disallowed tools
   * one at a time.
   */
  data class Refused(val toolName: String, val reason: String = "not enabled for this session") :
    ToolOutcome()

  /**
   * The tool was allowed to run but the underlying device action failed. [error] is a short,
   * human-readable reason (an exception message, or "no app found to handle this action").
   */
  data class Failed(val error: String) : ToolOutcome()
}

/**
 * Builds the map returned to the model for a `@Tool` method, truthful to [outcome]. [onSuccess]
 * supplies the extra echoed-input fields the prior unconditional-success maps included (e.g.
 * "to"/"subject"/"body" for sendEmail) -- included only on an actual success, so a refusal or
 * failure response never implies the input was acted on.
 */
private fun outcomeMap(
  outcome: ToolOutcome,
  onSuccess: Map<String, String> = emptyMap(),
): Map<String, String> =
  when (outcome) {
    is ToolOutcome.Success -> mapOf("result" to "success") + onSuccess
    is ToolOutcome.Refused ->
      mapOf(
        "result" to "refused",
        "error" to
          "Tool '${outcome.toolName}' is not available (${outcome.reason}). This action was not performed.",
      )
    is ToolOutcome.Failed -> mapOf("result" to "error", "error" to outcome.error)
  }

class MobileActionsTools(val onFunctionCalled: (Action) -> ToolOutcome) : ToolSet {
  @Tool(description = "Turn on flashlight")
  fun turnOnFlashlight(): Map<String, String> {
    Log.d(TAG, "turn on flashlight")

    return outcomeMap(onFunctionCalled(FlashlightOnAction()))
  }

  @Tool(description = "Turn off flashlight")
  fun turnOffFlashlight(): Map<String, String> {
    Log.d(TAG, "turn off flashlight")

    return outcomeMap(onFunctionCalled(FlashlightOffAction()))
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

    return outcomeMap(
      onFunctionCalled(
        CreateContactAction(
          firstName = firstName,
          lastName = lastName,
          phoneNumber = phoneNumber,
          email = email,
        )
      ),
      onSuccess =
        mapOf(
          "first_name" to firstName,
          "last_name" to lastName,
          "phone_number" to phoneNumber,
          "email" to email,
        ),
    )
  }

  @Tool(description = "Send email")
  fun sendEmail(
    @ToolParam(description = "Recipient email") to: String,
    @ToolParam(description = "Subject") subject: String,
    @ToolParam(description = "Body") body: String,
  ): Map<String, String> {
    Log.d(TAG, "send email. To: '$to', subject: '$subject', body: '$body'")

    return outcomeMap(
      onFunctionCalled(SendEmailAction(to = to, subject = subject, body = body)),
      onSuccess = mapOf("to" to to, "subject" to subject, "body" to body),
    )
  }

  @Tool(description = "Show location on map")
  fun showLocationOnMap(
    @ToolParam(description = "Place name or address") location: String
  ): Map<String, String> {
    Log.d(TAG, "Show location on map. Location: '$location'")

    return outcomeMap(
      onFunctionCalled(ShowLocationOnMap(location = location)),
      onSuccess = mapOf("location" to location),
    )
  }

  @Tool(description = "Open WiFi settings")
  fun openWifiSettings(): Map<String, String> {
    Log.d(TAG, "Open wifi settings")

    return outcomeMap(onFunctionCalled(OpenWifiSettingsAction()))
  }

  @Tool(description = "Create calendar event")
  fun createCalendarEvent(
    @ToolParam(description = "Datetime YYYY-MM-DDTHH:MM:SS") datetime: String,
    @ToolParam(description = "Title") title: String,
  ): Map<String, String> {
    Log.d(TAG, "Create calendar event. Datetime: '$datetime', title: '$title'")

    return outcomeMap(
      onFunctionCalled(CreateCalendarEventAction(datetime = datetime, title = title)),
      onSuccess = mapOf("datetime" to datetime, "title" to title),
    )
  }

  @Tool(description = "Set alarm")
  fun setAlarm(
    @ToolParam(description = "Hour 0-23") hour: Int,
    @ToolParam(description = "Minute 0-59") minute: Int,
    @ToolParam(description = "Label") label: String,
  ): Map<String, String> {
    Log.d(TAG, "Set alarm. Hour: $hour, minute: $minute, label: '$label'")

    return outcomeMap(
      onFunctionCalled(SetAlarmAction(hour = hour, minute = minute, label = label)),
      onSuccess = mapOf("hour" to hour.toString(), "minute" to minute.toString()),
    )
  }

  @Tool(description = "Set countdown timer")
  fun setTimer(
    @ToolParam(description = "Duration seconds") lengthSeconds: Int,
    @ToolParam(description = "Label") label: String,
  ): Map<String, String> {
    Log.d(TAG, "Set timer. Length: ${lengthSeconds}s, label: '$label'")

    return outcomeMap(
      onFunctionCalled(SetTimerAction(lengthSeconds = lengthSeconds, label = label)),
      onSuccess = mapOf("lengthSeconds" to lengthSeconds.toString()),
    )
  }

  @Tool(description = "Dial phone number")
  fun dialNumber(
    @ToolParam(description = "Phone number") phoneNumber: String
  ): Map<String, String> {
    Log.d(TAG, "Dial number: '$phoneNumber'")

    return outcomeMap(
      onFunctionCalled(DialNumberAction(phoneNumber = phoneNumber)),
      onSuccess = mapOf("phoneNumber" to phoneNumber),
    )
  }

  @Tool(description = "Send SMS")
  fun sendSms(
    @ToolParam(description = "Phone number") phoneNumber: String,
    @ToolParam(description = "Message") message: String,
  ): Map<String, String> {
    Log.d(TAG, "Send SMS. To: '$phoneNumber', message: '$message'")

    return outcomeMap(
      onFunctionCalled(SendSmsAction(phoneNumber = phoneNumber, message = message)),
      onSuccess = mapOf("phoneNumber" to phoneNumber, "message" to message),
    )
  }

  @Tool(description = "Open URL in browser")
  fun openUrl(
    @ToolParam(
      description = "URL with scheme"
    )
    url: String
  ): Map<String, String> {
    Log.d(TAG, "Open URL: '$url'")

    return outcomeMap(
      onFunctionCalled(OpenUrlAction(url = url)),
      onSuccess = mapOf("url" to url),
    )
  }

  @Tool(description = "Open Bluetooth settings")
  fun openBluetoothSettings(): Map<String, String> {
    Log.d(TAG, "Open Bluetooth settings")

    return outcomeMap(onFunctionCalled(OpenBluetoothSettingsAction()))
  }

  @Tool(description = "Open sound settings")
  fun openSoundSettings(): Map<String, String> {
    Log.d(TAG, "Open sound settings")

    return outcomeMap(onFunctionCalled(OpenSoundSettingsAction()))
  }
}
