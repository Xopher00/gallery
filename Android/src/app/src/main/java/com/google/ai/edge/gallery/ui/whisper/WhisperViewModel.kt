package com.google.ai.edge.gallery.ui.whisper

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.whisper.WhisperEngine
import com.google.ai.edge.gallery.whisper.toFloat32
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class WhisperUiState(
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val transcript: String = "",
    val errorMessage: String? = null,
)

@HiltViewModel
class WhisperViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(WhisperUiState())
    val uiState: StateFlow<WhisperUiState> = _uiState.asStateFlow()

    private var recordJob: Job? = null
    private val pcmBuffer = mutableListOf<Short>()
    private var audioRecord: AudioRecord? = null

    fun startRecording() {
        if (_uiState.value.isRecording) return
        _uiState.value = _uiState.value.copy(
            isRecording = true,
            isTranscribing = false,
            errorMessage = null,
        )
        pcmBuffer.clear()

        recordJob = viewModelScope.launch(Dispatchers.IO) {
            val bufferSize = AudioRecord.getMinBufferSize(
                WhisperEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                WhisperEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            audioRecord = recorder
            val chunk = ShortArray(bufferSize / 2)
            recorder.startRecording()

            while (_uiState.value.isRecording) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read > 0) {
                    synchronized(pcmBuffer) {
                        for (i in 0 until read) pcmBuffer.add(chunk[i])
                    }
                }
            }
            recorder.stop()
            recorder.release()
            audioRecord = null
        }
    }

    fun stopRecordingAndTranscribe(model: Model) {
        _uiState.value = _uiState.value.copy(isRecording = false, isTranscribing = true)

        viewModelScope.launch(Dispatchers.Default) {
            recordJob?.join()

            val samples: FloatArray
            synchronized(pcmBuffer) {
                samples = pcmBuffer.toShortArray().toFloat32()
                pcmBuffer.clear()
            }

            if (samples.isEmpty()) {
                _uiState.value = _uiState.value.copy(isTranscribing = false, errorMessage = "No audio recorded")
                return@launch
            }

            val engine = model.instance as? WhisperEngine ?: run {
                _uiState.value = _uiState.value.copy(isTranscribing = false, errorMessage = "Model not loaded")
                return@launch
            }

            val result = engine.transcribe(samples)
            _uiState.value = _uiState.value.copy(
                isTranscribing = false,
                transcript = result.ifEmpty { _uiState.value.transcript },
                errorMessage = if (result.isEmpty()) "Could not transcribe audio" else null,
            )
        }
    }

    fun clearTranscript() {
        _uiState.value = _uiState.value.copy(transcript = "", errorMessage = null)
    }

    private fun MutableList<Short>.toShortArray(): ShortArray {
        val arr = ShortArray(size)
        for (i in indices) arr[i] = this[i]
        return arr
    }
}
