package fr.rythmo

import android.os.Bundle
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
import fr.rythmo.ui.RythmoWorkspace
import fr.rythmo.ui.RythmoTheme

class MainActivity : ComponentActivity() {
    private val preparation: TeacherPreparationViewModel by viewModels()
    private val teacherSettings: TeacherSettingsViewModel by viewModels {
        viewModelFactory { initializer { TeacherSettingsViewModel { LocalTeacherLock(File(filesDir, "teacher-lock.properties")) } } }
    }

    override fun onStop() {
        if (!isChangingConfigurations) teacherSettings.background()
        super.onStop()
    }

    private fun handleAction(intent: android.content.Intent?) {
        if (intent?.action == TeacherService.ACTION_STOP_REQUEST) teacherSettings.requestServerStop()
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAction(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent { RythmoTheme { RythmoWorkspace(settings = teacherSettings, onDocument = preparation::readDocument) } }
    }
}
