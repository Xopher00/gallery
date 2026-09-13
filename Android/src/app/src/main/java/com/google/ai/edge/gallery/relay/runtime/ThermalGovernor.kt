// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

// Decision functions below take primitives, not Context/PowerManager, so they run on the JVM.
object ThermalGovernor {

    private const val HEADROOM_FORECAST_SECONDS = 10
    private const val HEADROOM_CACHE_MS = 2_000L
    private const val HEADROOM_SHED_THRESHOLD = 0.95f
    private const val DECODE_FLOOR_TOKENS_PER_SECOND = 3.0
    const val MODERATE_START_DELAY_MS = 1_500L

    /** Decode-tokens-per-second EMA, trusted only once [minSamples] turns have landed. */
    class DecodeRateTracker(private val alpha: Double = 0.3, private val minSamples: Int = 3) {
        private var ema: Double? = null
        private var sampleCount = 0

        @Synchronized
        fun record(tokensPerSecond: Double) {
            sampleCount++
            ema = ema?.let { it + alpha * (tokensPerSecond - it) } ?: tokensPerSecond
        }

        @Synchronized
        fun average(): Double? = ema.takeIf { sampleCount >= minSamples }
    }

    sealed class GateDecision {
        data class Shed(val retryAfterSeconds: Int) : GateDecision()
        object Delay : GateDecision()
        object Proceed : GateDecision()
    }

    @Volatile private var thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    @Volatile private var cachedHeadroom: Float? = null
    @Volatile private var headroomCachedAtMs: Long = 0L

    private val decodeRateTracker = DecodeRateTracker()

    // Guards against a re-entrant service start stacking a second listener.
    fun registerListener(context: Context) {
        if (thermalListener != null) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val listener = PowerManager.OnThermalStatusChangedListener { status -> thermalStatus = status }
        powerManager.addThermalStatusListener(listener)
        thermalListener = listener
        thermalStatus = powerManager.currentThermalStatus
    }

    fun unregisterListener(context: Context) {
        val listener = thermalListener ?: return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.removeThermalStatusListener(listener)
        thermalListener = null
    }

    // Cached because the platform rate-limits this call.
    fun headroom(context: Context): Float? {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedHeadroom
        if (now - headroomCachedAtMs < HEADROOM_CACHE_MS) return cached
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val fresh = powerManager.getThermalHeadroom(HEADROOM_FORECAST_SECONDS).takeUnless { it.isNaN() }
        cachedHeadroom = fresh
        headroomCachedAtMs = now
        return fresh
    }

    fun recordDecodeRate(tokensPerSecond: Double) = decodeRateTracker.record(tokensPerSecond)

    fun currentDecodeAverage(): Double? = decodeRateTracker.average()

    fun currentThermalStatus(): Int = thermalStatus

    fun gateDecision(context: Context): GateDecision {
        val status = thermalStatus
        val headroomValue = headroom(context)
        val decodeAverage = currentDecodeAverage()
        return when {
            shouldShed(status, headroomValue, decodeAverage) -> GateDecision.Shed(retryAfterSeconds(status))
            shouldDelay(status) -> GateDecision.Delay
            else -> GateDecision.Proceed
        }
    }

    fun shouldStopNow(): Boolean = shouldStop(thermalStatus)

    // Status checked unconditionally first, independent of the headroom/decode thresholds below.
    fun shouldShed(status: Int, headroom: Float?, decodeAverage: Double?): Boolean {
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) return true
        if (headroom != null && headroom >= HEADROOM_SHED_THRESHOLD) return true
        if (decodeAverage != null && decodeAverage < DECODE_FLOOR_TOKENS_PER_SECOND) return true
        return false
    }

    fun shouldDelay(status: Int): Boolean = status == PowerManager.THERMAL_STATUS_MODERATE

    fun shouldStop(status: Int): Boolean = status >= PowerManager.THERMAL_STATUS_CRITICAL

    fun retryAfterSeconds(status: Int): Int = when {
        status >= PowerManager.THERMAL_STATUS_CRITICAL -> 30
        status >= PowerManager.THERMAL_STATUS_SEVERE -> 10
        else -> 8
    }
}
