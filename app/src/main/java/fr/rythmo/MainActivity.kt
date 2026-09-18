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
    private val teacherSettings: TeacherSettingsViewModel by viewModels {
        viewModelFactory { initializer { TeacherSettingsViewModel { LocalTeacherLock(File(filesDir, "teacher-lock.properties")) } } }
    }

    override fun onStop() {
        if (!isChangingConfigurations) teacherSettings.background()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent { RythmoTheme { RythmoWorkspace(settings = teacherSettings) } }
    }
}
