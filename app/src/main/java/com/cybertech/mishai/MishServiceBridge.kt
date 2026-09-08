package com.cybertech.mishai

/**
 * Static bridge so the Compose UI can observe speech events coming from the
 * background MishService without tight coupling.
 */
object MishServiceBridge {
    var onUserSpeech: ((String) -> Unit)? = null
    var onMishReply: ((String, Mood) -> Unit)? = null
    var onServiceState: ((Boolean) -> Unit)? = null
}
