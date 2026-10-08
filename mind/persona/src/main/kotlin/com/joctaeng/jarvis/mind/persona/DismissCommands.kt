package com.joctaeng.jarvis.mind.persona

/** Mantido por compatibilidade: veja [VoiceCommands]. */
object DismissCommands {
    fun matches(utterance: String): Boolean = VoiceCommands.parse(utterance) == VoiceCommand.DISMISS
}
