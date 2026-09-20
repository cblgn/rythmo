package fr.rythmo

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Looper
import fr.rythmo.sync.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class TeacherServiceTest {
 @get:Rule val temp=TemporaryFolder()
 class Host:TeacherService() {
  override fun loadIdentity():TeacherTls { failure?.let { throw it };return identity }
  companion object { lateinit var identity:TeacherTls;var failure:Exception?=null }
 }
 @Before fun setup() { resetAndroidFixtures();Host.failure=null;TeacherService.status=TeacherStatus(false) }
 private fun await(condition:()->Boolean) {
  val deadline=System.nanoTime()+15_000_000_000
  while(!condition() && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) }
  assertTrue(condition())
 }
 @Test fun `foreground host retains sharing when settings lock and removes its notification on explicit stop`() {
  Host.identity=JvmTeacherIdentity.load(temp.newFolder())
  val controller=Robolectric.buildService(Host::class.java).create();val service=controller.get()
  try {
   await { TeacherService.status.running }
   val manager=service.getSystemService(NotificationManager::class.java)
   val notification=shadowOf(manager).getNotification(42)
   assertEquals("Rythmo · Professeur",notification.extras.getString("android.title"))
   assertEquals("Arrêter…",notification.actions.single().title)
   assertNull(service.onBind(Intent()))
   assertEquals(Service.START_NOT_STICKY,service.onStartCommand(Intent().putExtra("nearby",true),0,1))
   await { TeacherService.nearby!=null };assertTrue(TeacherService.status.running)
   service.advertiseNearby();shadowOf(Looper.getMainLooper()).idle()
   val lock=LocalTeacherLock(java.io.File(temp.newFolder(),"lock"));val settings=TeacherSettingsViewModel { lock }
   settings.background();assertTrue(TeacherService.status.running)
   val vm=androidx.lifecycle.ViewModelStore();vm.put("settings",settings);vm.clear()
  } finally { controller.destroy() }
  assertFalse(TeacherService.status.running);assertNull(TeacherService.nearby)
  assertNull(shadowOf(service.getSystemService(NotificationManager::class.java)).getNotification(42))
 }
 @Test fun `identity failure reports the error and stops the foreground service`() {
  Host.failure=IllegalStateException("Storage unavailable")
  val controller=Robolectric.buildService(Host::class.java).create();val service=controller.get()
  try { await { TeacherService.status.error!=null && shadowOf(service).isStoppedBySelf };assertFalse(TeacherService.status.running) }
  finally { controller.destroy() }
 }
}
