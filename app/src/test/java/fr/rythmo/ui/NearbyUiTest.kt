package fr.rythmo.ui

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import fr.rythmo.*
import fr.rythmo.sync.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w800dp-h1000dp")
class NearbyUiTest {
 @get:Rule val compose=createComposeRule()
 private val models=ViewModelStore()
 @After fun close() { models.clear() }
 class Fake:LocalTransport {
  override val state=MutableStateFlow(TransportState())
  var selected:String?=null;var decision:Boolean?=null
  override fun discover(name:String) { state.value=TransportState(phase=ConnectionPhase.SEARCHING,endpoints=listOf(Endpoint("a","Prof Alice"),Endpoint("b","Prof Basile"))) }
  override fun connect(endpoint:Endpoint,name:String) { selected=endpoint.id;state.value=TransportState(phase=ConnectionPhase.ASSOCIATING,associations=listOf(Association(endpoint,"1234"))) }
  override fun confirm(endpointId:String,accept:Boolean) { decision=accept;state.value=TransportState() }
  override fun disconnect() { state.value=TransportState() }
  override fun close()=disconnect()
  override fun advertise(name:String,receive:(String,SyncMessage)->SyncMessage) { error("Not a host") }
  override fun exchange(request:SyncMessage):SyncMessage { error("Offline test") }
 }
 @Test fun `discovered teacher selection requires visible association confirmation and supports rejection`() {
  val app=RuntimeEnvironment.getApplication();val transport=Fake()
  val m=SessionViewModel(app,transport,transport);models.put("session",m)
  val s=TeacherSettingsViewModel { LocalTeacherLock(File(app.cacheDir,"lock")) };models.put("settings",s)
  compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle();m.ready && s.ready }
  m.retrieveNearby();compose.setContent { RythmoTheme { NearbyDialogs(m,s) } }
  compose.onNodeWithText("Prof Basile").performClick();assertEquals("b",transport.selected)
  compose.onNodeWithText("1234").assertExists();compose.onNodeWithText("Rejoindre ce professeur ?").assertExists()
  compose.onNodeWithText("Refuser").performClick();assertEquals(false,transport.decision)
  m.retrieveNearby();compose.onNodeWithText("Prof Alice").performClick();compose.onNodeWithText("Les codes correspondent").performClick()
  assertEquals(true,transport.decision)
  transport.state.value=TransportState(phase=ConnectionPhase.LOST)
  compose.onNodeWithText("Connexion perdue · course conservée").assertExists();compose.onNodeWithText("Réessayer").assertExists()
  compose.onNodeWithText("Fermer").performClick();assertFalse(m.nearbyPanel)
 }
 @Test fun `every connection phase has a user facing status and supplied errors take precedence`() {
  val messages=ConnectionPhase.entries.map { nearbyStatus(TransportState(phase=it)) }
  assertEquals(messages.size,messages.toSet().size);assertTrue(messages.all { it.isNotBlank() })
  assertEquals("Permission required",nearbyStatus(TransportState(error="Permission required")))
 }
}
