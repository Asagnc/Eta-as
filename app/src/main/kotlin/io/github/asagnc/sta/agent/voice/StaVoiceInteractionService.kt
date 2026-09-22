package io.github.asagnc.sta.agent.voice

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

class StaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        activeService = this
        if (Build.VERSION.SDK_INT >= 37) {
            setInvocationEffectEnabled(true)
        }
    }

    override fun onShutdown() {
        if (activeService === this) activeService = null
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        startActivity(
            Intent(this, StaVoiceAssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun showStaSession() {
        showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
    }

    companion object {
        @Volatile
        private var activeService: StaVoiceInteractionService? = null

        internal fun requestSession(): Boolean {
            val service = activeService ?: return false
            service.showStaSession()
            return true
        }
    }
}

class StaVoiceAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StaVoiceInteractionService.requestSession()
        finish()
    }
}
