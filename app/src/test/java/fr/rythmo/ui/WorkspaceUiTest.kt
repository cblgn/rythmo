package fr.rythmo.ui

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import fr.rythmo.*
import fr.rythmo.session.*
import fr.rythmo.sync.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w800dp-h1400dp",shadows=[RecordingPdfDocument::class])
class WorkspaceUiTest {
 @get:Rule val compose=createComposeRule()
 private val models=ViewModelStore()
 private val app:Application get()=RuntimeEnvironment.getApplication()
 @Before fun reset() { resetAndroidFixtures() }
 @After fun close() { models.clear();TeacherService.status=TeacherStatus(false) }
 private fun await(condition:()->Boolean) { compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle();condition() } }
 private fun launch(archive:ClientArchive):Pair<SessionViewModel,TeacherSettingsViewModel> {
  JsonFile(File(app.filesDir,"sessions/client.json"),ClientArchive.serializer()) { ClientArchive() }.write(archive)
  val m=SessionViewModel(app,MessageTransport { error("Offline test") });models.put("session",m)
  val lock=LocalTeacherLock(File(app.cacheDir,"lock"));lock.initialize("123456","123456");lock.acknowledgeRecovery()
  val s=TeacherSettingsViewModel { lock };models.put("settings",s)
  val i=RythmoViewModel(SavedStateHandle());models.put("individual",i)
  await { m.ready && s.ready }
  compose.setContent { RythmoTheme { RythmoWorkspace(m,s,i) } }
  return m to s
 }
 @Test fun `group actions capture selected pupils and require teacher code for abandonment`() {
  val session=demoSession().copy(pupils=demoSession().pupils.take(2),lapCount=2,distanceMeters=1000)
  val group=RaceGroup(session=session,runners=session.pupils.map { RunnerRecord(pupil=it) },claimed=true)
  val (m,_)=launch(ClientArchive(session=session,groups=listOf(group),activeGroupId=group.id,transport="https",teacherAccess=TeacherAccess.fromCode("654321")))
  compose.onNodeWithText("DÉMARRER LA COURSE").performClick();await { m.raceRunning }
  compose.runOnIdle { m.dismissMessage() };SystemClock.sleep(1500);compose.onNodeWithText("Passage groupé").performClick()
  compose.onNodeWithText("Valider · 2").assertIsEnabled()
  val second=group.runners[1];compose.onNodeWithText("${second.pupil.firstName} ${second.pupil.lastName.first()}.").performClick()
  compose.onNodeWithText("Valider · 1").performClick();await { m.archive.activeGroup!!.runners[0].rawCumulativeMs.size==1 }
  assertTrue(m.archive.activeGroup!!.runners[1].rawCumulativeMs.isEmpty())
  compose.onNodeWithText("Passage groupé").performClick();compose.onNodeWithText("Annuler").performClick();assertNull(m.capturedPassage)
  compose.onAllNodesWithText("Professeur").onLast().performClick()
  compose.onNodeWithText("Abandon · ${second.pupil.label}").performClick()
  compose.onNodeWithText("Code professeur · 6 chiffres").performTextInput("000000")
  compose.onNodeWithText("Confirmer").performClick();await { m.teacherError!=null };assertFalse(m.archive.activeGroup!!.runners[1].abandoned)
  compose.onNodeWithText("Code professeur · 6 chiffres").performTextReplacement("654321")
  compose.onNodeWithText("Confirmer").performClick();await { m.archive.activeGroup!!.runners[1].abandoned }
  compose.onNodeWithText("${second.pupil.firstName} ${second.pupil.lastName.first()}.").performClick()
  compose.onNodeWithText("Abandon · non noté").assertExists();compose.onNodeWithText("Fermer").performClick()
  compose.runOnIdle { m.dismissMessage() };SystemClock.sleep(1500);compose.onNodeWithText("Passage groupé").performClick();compose.onNodeWithText("Valider · 1").performClick()
  await { m.archive.activeGroup!!.complete };compose.onNodeWithText("Série terminée").assertExists()
  compose.onNodeWithText("Autre groupe").performClick();await { m.archive.activeGroup==null };assertEquals(1,m.archive.groups.size)
 }
 @Test fun `preparation retains past groups and does not allow selecting assigned pupils`() {
  val session=demoSession().copy(pupils=demoSession().pupils.take(3))
  val group=RaceGroup(session=session,runners=listOf(RunnerRecord(pupil=session.pupils[0],abandoned=true)))
  launch(ClientArchive(session=session,groups=listOf(group),transport="https",pairingCode="fictional"))
  compose.onNodeWithText("Déjà affecté à un groupe").assertExists()
  compose.onNodeWithText(session.pupils[1].label).performScrollTo().performClick()
  compose.onNodeWithText("Valider le groupe · 1 élèves").assertIsEnabled()
  compose.onNodeWithText(session.pupils[1].label).performClick();compose.onNodeWithText("Valider le groupe · 0 élèves").assertIsNotEnabled()
  compose.onNodeWithText("Séries conservées").performScrollTo().assertExists()
  compose.onNodeWithText("Envoyer les bilans au professeur").performClick()
  compose.onNodeWithText("Synchroniser la protection").assertExists();compose.onNodeWithText("Annuler").performClick()
 }
 @Test fun `teacher navigation locks again on return and exposes only enabled server actions`() {
  val (m,s)=launch(ClientArchive(transport="nearby"))
  compose.onNodeWithContentDescription("Menu").performClick();compose.onNodeWithText("Accès professeur").performClick()
  compose.onNodeWithText("PIN professeur").performTextInput("123456");compose.onNodeWithText("Déverrouiller").performClick();await { s.unlocked }
  compose.onNodeWithText("Appareils").performClick();compose.onNodeWithText("Voir les appareils connus").assertIsNotEnabled()
  compose.onNodeWithText("Bilans").performClick();compose.onNodeWithText("Résultats et PDF").assertIsNotEnabled()
  compose.onNodeWithText("Autres options").performScrollTo().performClick()
  compose.onNodeWithText("Adresse HTTPS").performScrollTo().performTextReplacement("http://invalid")
  compose.onNodeWithText("Vérifier le serveur").performScrollTo().performClick();await { m.message!=null }
  compose.onNodeWithText("Masquer les options").performScrollTo().performClick()
  compose.runOnIdle { m.dismissMessage() };compose.onNodeWithText("Retour aux élèves").performClick();compose.waitForIdle();assertFalse(s.unlocked)
  compose.onNodeWithText("Récupérer la séance").assertExists()
 }
}
