package fr.rythmo

import android.app.Application
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import fr.rythmo.session.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class TeacherPreparationViewModelTest {
 private val app:Application get()=RuntimeEnvironment.getApplication()
 private val models=ViewModelStore()
 @Before fun reset() { resetAndroidFixtures() }
 @After fun close() { models.clear() }
 private fun waitFor(condition:()->Boolean) {
  val deadline=System.nanoTime()+10_000_000_000
  while(!condition() && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
  assertTrue(condition())
 }
 @Test fun `locked preparation cannot mutate and publication preserves previous sessions`() {
  val lock=LocalTeacherLock(File(app.cacheDir,"lock"));lock.initialize("123456","123456");lock.acknowledgeRecovery()
  val s=TeacherSettingsViewModel { lock };models.put("settings",s)
  val m=TeacherPreparationViewModel(app);models.put("prep",m);waitFor { m.ready && s.ready }
  assertThrows(IllegalStateException::class.java) { m.edit(m.data.draft,s) }
  m.review(s);waitFor { m.error!=null };assertNull(m.preview)
  s.submit("123456");waitFor { s.unlocked }
  val table=PerformanceTable(name="Fictional",distanceMeters=1000,sourceMaxTenths=70,tables=Sex.entries.associateWith { listOf(PerformanceThreshold(60000,70)) })
  m.importTable(table,s);waitFor { m.data.tables.isNotEmpty() }
  m.edit(m.data.draft.copy(classId=m.classes.first().id),s);m.review(s);waitFor { m.preview!=null }
  val previous=AndroidTeacherRepository.get(app).store.state.sessions
  var sharing=false;m.publish(s,{true}) { sharing=true };waitFor { m.error!=null };assertFalse(sharing)
  assertEquals(previous,AndroidTeacherRepository.get(app).store.state.sessions)
  m.publish(s,{false}) { sharing=true };waitFor { m.sessionCode!=null };assertTrue(sharing)
  assertTrue(AndroidTeacherRepository.get(app).store.state.sessions.containsAll(previous))
  m.hideSessionCode();m.showSessionCode(s);assertNotNull(m.sessionCode)
  s.background();assertThrows(IllegalStateException::class.java) { m.showSessionCode(s) }
 }
 @Test fun `oversized import fails without changing draft or reusable tables`() {
  val m=TeacherPreparationViewModel(app);models.put("prep",m);waitFor { m.ready }
  val before=m.data;val file=File(app.filesDir,"reports/oversized.csv").also { it.parentFile!!.mkdirs();it.writeBytes(ByteArray(2_000_001)) }
  m.readDocument(FileProvider.getUriForFile(app,"${app.packageName}.files",file));waitFor { m.error!=null }
  assertTrue(m.error!!.contains("2 Mo"));assertNull(m.workbook);assertEquals(before,m.data)
  m.closeImport();m.cancelReview();assertNull(m.preview)
 }
}
