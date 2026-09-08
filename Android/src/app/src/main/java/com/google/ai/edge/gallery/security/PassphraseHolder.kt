package com.google.ai.edge.gallery.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PassphraseHolder {
    private val _isSet = MutableStateFlow(false)
    val isSet: StateFlow<Boolean> = _isSet.asStateFlow()

    @Volatile private var passphrase: ByteArray? = null

    fun set(bytes: ByteArray) {
        passphrase = bytes.copyOf()
        _isSet.value = true
    }

    fun get(): ByteArray? = passphrase

    fun clear() {
        passphrase?.fill(0)
        passphrase = null
        _isSet.value = false
    }
}
