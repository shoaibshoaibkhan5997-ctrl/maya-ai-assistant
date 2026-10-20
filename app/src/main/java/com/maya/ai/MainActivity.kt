package com.maya.ai

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maya.ai.overlay.FloatingBubbleService
import com.maya.ai.selfimprove.SelfImprove
import com.maya.ai.ui.Screen
import com.maya.ai.ui.chat.ChatScreen
import com.maya.ai.ui.floating.FloatingBubbleScreen
import com.maya.ai.ui.home.HomeScreen
import com.maya.ai.ui.screencontrol.ScreenControlScreen
import com.maya.ai.ui.selfimprove.SelfImproveScreen
import com.maya.ai.ui.settings.AboutScreen
import com.maya.ai.ui.settings.ApiSettingsScreen
import com.maya.ai.ui.settings.PrivacyScreen
import com.maya.ai.ui.settings.VoiceSettingsScreen
import com.maya.ai.ui.theme.MayaTheme
import com.maya.ai.viewmodel.ChatViewModel
import com.maya.ai.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {
    private val chatVm: ChatViewModel by viewModels()
    private val settingsVm: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            MayaTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                var origin by rememberSaveable { mutableStateOf(Screen.HOME) }
                val go: (Screen) -> Unit = { target -> origin = screen; screen = target }
                val back: () -> Unit = {
                    screen = if (screen == Screen.CHAT || origin == screen) Screen.HOME else origin
                    origin = Screen.HOME
                }

                // "Maya, khud ko behtar banao" typed or spoken in chat opens the Self Improve screen.
                val openText by SelfImprove.openRequest.collectAsStateWithLifecycle()
                LaunchedEffect(openText) {
                    if (openText != null) {
                        SelfImprove.consumeOpenRequest()
                        if (screen != Screen.SELF_IMPROVE) { origin = screen; screen = Screen.SELF_IMPROVE }
                    }
                }

                BackHandler(enabled = screen != Screen.HOME) { back() }
                when (screen) {
                    Screen.HOME -> HomeScreen(chatVm, go)
                    Screen.CHAT -> ChatScreen(chatVm) { go(it) }
                    Screen.VOICE -> VoiceSettingsScreen(settingsVm, back)
                    Screen.API -> ApiSettingsScreen(settingsVm, back)
                    Screen.SCREEN_CONTROL -> ScreenControlScreen(settingsVm, back)
                    Screen.FLOATING -> FloatingBubbleScreen(back)
                    Screen.SELF_IMPROVE -> SelfImproveScreen(back)
                    Screen.PRIVACY -> PrivacyScreen(settingsVm, back)
                    Screen.ABOUT -> AboutScreen(back)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // While the MAYA screen is visible the app owns the microphone, so the bubble's mic stops.
        FloatingBubbleService.appVisible = true
        FloatingBubbleService.stopListening(this)
    }

    override fun onStop() {
        FloatingBubbleService.appVisible = false
        // Screen rotation also stops the activity; only a real move to the background ends Voice Mode.
        if (!isChangingConfigurations) chatVm.onAppBackground()
        super.onStop()
    }
}
