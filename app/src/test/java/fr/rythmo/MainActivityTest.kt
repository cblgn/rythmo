package fr.rythmo

import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MainActivityTest {
 @Test fun `notification requests protected stop and leaving activity locks teacher access`() {
  resetAndroidFixtures()
  val controller=Robolectric.buildActivity(MainActivity::class.java,Intent().setAction(TeacherService.ACTION_STOP_REQUEST)).setup()
  val settings=ViewModelProvider(controller.get())[TeacherSettingsViewModel::class.java]
  assertTrue(settings.stopServerRequested);assertFalse(settings.unlocked)
  settings.cancelServerStop();controller.newIntent(Intent().setAction(TeacherService.ACTION_STOP_REQUEST))
  assertTrue(settings.requested);assertTrue(settings.stopServerRequested)
  controller.pause().stop();assertFalse(settings.requested);assertFalse(settings.unlocked)
  controller.destroy();shadowOf(Looper.getMainLooper()).idle()
 }
}
