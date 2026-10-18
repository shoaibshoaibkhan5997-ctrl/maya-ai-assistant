package com.maya.ai

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.maya.ai.ui.Screen
import com.maya.ai.ui.chat.ChatScreen
import com.maya.ai.ui.screencontrol.ScreenControlScreen
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
                var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
                BackHandler(enabled = screen != Screen.CHAT) { screen = Screen.CHAT }
                val back = { screen = Screen.CHAT }
                when (screen) {
                    Screen.CHAT -> ChatScreen(chatVm) { screen = it }
                    Screen.VOICE -> VoiceSettingsScreen(settingsVm, back)
                    Screen.API -> ApiSettingsScreen(settingsVm, back)
                    Screen.SCREEN_CONTROL -> ScreenControlScreen(settingsVm, back)
                    Screen.PRIVACY -> PrivacyScreen(settingsVm, back)
                    Screen.ABOUT -> AboutScreen(back)
                }
            }
        }
    }

    override fun onStop() {
        // Screen rotation also stops the activity; only a real move to the background ends Voice Mode.
        if (!isChangingConfigurations) chatVm.onAppBackground()
        super.onStop()
    }
}
