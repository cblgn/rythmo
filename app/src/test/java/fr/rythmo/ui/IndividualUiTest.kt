package fr.rythmo.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import fr.rythmo.*
import fr.rythmo.domain.TimingMode
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w800dp-h1600dp")
class IndividualUiTest {
 @get:Rule val compose=createComposeRule()
 private val store=ViewModelStore()
 private fun model()=RythmoViewModel(SavedStateHandle()).also { store.put("individual",it) }
 @After fun close() { store.clear() }
 @Test fun `identity manual entries and reset are connected to the real model`() {
  val m=model();compose.setContent { RythmoTheme { RythmoApp(m,{}) } }
  compose.onNodeWithText("COMMENCER").assertIsNotEnabled()
  compose.onNodeWithText("Nom").performTextInput("Exemple")
  compose.onNodeWithText("Prénom").performTextInput("Alice")
  compose.onNodeWithText("Classe").performClick();compose.onNodeWithText(SCHOOL_CLASSES.first()).performClick()
  compose.onNodeWithText("COMMENCER").performClick()
  compose.onNodeWithContentDescription("Ajouter le passage").performClick()
  assertNotNull(m.state.inputError)
  for(seconds in listOf("29","29","27","29","27")) {
   compose.onNodeWithContentDescription("Minutes").performTextInput("1")
   compose.onNodeWithContentDescription("Secondes").performTextInput(seconds)
   compose.onNodeWithContentDescription("Ajouter le passage").performClick()
  }
  assertEquals(441000L,m.state.result!!.totalMs)
  compose.onNodeWithText("VALIDER L’ÉVALUATION").assertIsEnabled()
  compose.onNodeWithText("Nouvelle évaluation").performScrollTo().performClick()
  compose.onNodeWithText("Annuler").performClick();assertEquals(5,m.state.cumulativeTimesMs.size)
  compose.onNodeWithText("Nouvelle évaluation").performClick();compose.onNodeWithText("Recommencer").performClick()
  compose.onNodeWithText("COMMENCER").assertIsNotEnabled();assertEquals("",m.state.firstName)
 }
 @Test fun `automatic mode starts explicitly and changing mode confirms reset while retaining identity`() {
  val m=model();m.setFirstName("Alice");m.setLastName("Exemple");m.setSchoolClass(SCHOOL_CLASSES.first())
  compose.setContent { RythmoTheme { RythmoApp(m,{}) } }
  compose.onNodeWithText("Automatique").performScrollTo().performClick();compose.onNodeWithText("COMMENCER").performClick()
  compose.onNodeWithText("DÉMARRER LE CHRONO").performClick();assertTrue(m.state.timerRunning)
  compose.onNodeWithText("‹ Élève").performClick();compose.onNodeWithText("Manuel").performScrollTo().performClick()
  compose.onNodeWithText("Changer de mode ?").assertExists();compose.onNodeWithText("Recommencer").performClick()
  assertEquals(TimingMode.MANUAL,m.state.timingMode);assertFalse(m.state.timerRunning);assertEquals("Alice",m.state.firstName)
 }
}
