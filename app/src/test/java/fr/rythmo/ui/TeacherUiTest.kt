package fr.rythmo.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import fr.rythmo.*
import fr.rythmo.session.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h1200dp")
class TeacherUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun <T: ViewModel> keep(m: T): T = m.also { models.put(newId(), it) }
    @Before fun reset() { resetAndroidFixtures() }
 @After fun close() { models.clear() }
    private fun settings(configured: Boolean = true): TeacherSettingsViewModel {
        val lock = LocalTeacherLock(File(app.cacheDir, "lock-${newId()}"))
        if (configured) { lock.initialize("123456", "123456"); lock.acknowledgeRecovery() }
        return keep(TeacherSettingsViewModel { lock }).also { m -> await { m.ready } }
    }
    private fun unlock(m: TeacherSettingsViewModel) { m.submit("123456"); await { !m.busy }; m.requireUnlocked() }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); condition() }
    }
    @Test fun `PIN setup requires recovery acknowledgement then rejects wrong PIN and offers recovery`() {
        val m = settings(false)
        compose.setContent { RythmoTheme { TeacherLockScreen(m, false) } }
        compose.onNodeWithText("Enregistrer le PIN").assertIsNotEnabled()
        compose.onNodeWithText("Nouveau PIN").performTextInput("123456")
        compose.onNodeWithText("Confirmer le PIN").performTextInput("123456")
        compose.onNodeWithText("Enregistrer le PIN").performClick()
        await { m.recoveryCode != null }; val recovery = requireNotNull(m.recoveryCode)
        compose.onNodeWithText("Terminer et verrouiller").assertIsNotEnabled()
        compose.onNode(isToggleable()).performClick();compose.onNodeWithText("Terminer et verrouiller").performClick()
        await { m.setupComplete && !m.busy }
        compose.onNodeWithText("PIN professeur").performTextInput("000000")
        compose.onNodeWithText("Déverrouiller").performClick();await { m.error != null }
        compose.onNodeWithText("PIN incorrect.").assertExists();assertFalse(m.unlocked)
        compose.onNodeWithText("PIN oublié").performClick()
        compose.onNodeWithText("Code de secours").performTextInput(recovery)
        compose.onNodeWithText("Nouveau PIN").performTextInput("654321")
        compose.onNodeWithText("Confirmer le PIN").performTextInput("654321")
        compose.onNodeWithText("Enregistrer le PIN").performClick();await { m.unlocked }
        assertNotEquals(recovery,m.recoveryCode)
    }
    @Test fun `native form imports a class and table then freezes the reviewed assessment`() {
        val s=settings();unlock(s)
        val prep=keep(TeacherPreparationViewModel(app));await { prep.ready }
        var published=0
        compose.setContent { RythmoTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TeacherPreparationForm(s,false,{}, { published++ },prep)
        } } }
        fun stage(kind:String,name:String,content:String) {
            prep.beginImport(kind,s)
            val file=File(app.filesDir,"reports/$name").also { it.parentFile!!.mkdirs();it.writeText(content) }
            prep.readDocument(FileProvider.getUriForFile(app,"${app.packageName}.files",file))
            await { prep.workbook != null || prep.error != null };assertNull(prep.error)
        }
        stage("class","fiction.csv","NOM;Prénom;Sexe\nExemple;Alice;F\nExemple;Basile;M")
        compose.onNodeWithText("Nom de la classe").performTextInput("3e Fiction")
        compose.onNodeWithText("Vérifier l’import").performScrollTo().performClick()
        compose.onNodeWithText("2 élèves").assertExists()
        compose.onNodeWithText("Enregistrer").performClick();await { prep.workbook == null }
        assertEquals(2,prep.classes.first { it.name=="3e Fiction" }.pupils.size)
        stage("performance","fiction-table.csv","bad;7\n2:00;6")
        compose.onNodeWithText("Nom du barème").performTextInput("Fictional")
        compose.onNodeWithText("Corriger un temps à l’import").performScrollTo().performClick()
        compose.onNodeWithText("Temps corrigé (min:sec)").performScrollTo().performTextInput("1:00")
        compose.onNodeWithText("Appliquer à l’aperçu").performScrollTo().performClick()
        compose.onNodeWithText("Profil à corriger").performScrollTo()
        compose.onAllNodesWithText("Filles").onLast().performClick()
        compose.onNodeWithText("Garçons").performClick()
        compose.onNodeWithText("Appliquer à l’aperçu").performScrollTo().performClick()
        compose.onNodeWithText("Vérifier l’import").performScrollTo().performClick()
        compose.onNodeWithText("Enregistrer").assertIsEnabled().performClick()
        await { prep.workbook == null }
        assertEquals(60000L,prep.data.tables.last().tables.getValue(Sex.GIRL).first().timeMs)
        compose.onNodeWithText("Note totale sur").performScrollTo().performTextReplacement("13")
        compose.onNodeWithText("Vérifier ma séance").performScrollTo().performClick();await { prep.preview != null }
        assertEquals(80,prep.preview!!.assessment!!.performanceMaxTenths)
        // Permission UI is tested separately; invoke the same durable publication action here.
        prep.publish(s,{false}) { published++ };await { prep.sessionCode != null }
        compose.onNodeWithText("Code des bilans").assertExists();assertEquals(1,published)
        compose.onNodeWithText("Fermer").performClick();assertNull(prep.sessionCode)
        val frozen=AndroidTeacherRepository.get(app).store.state.sessions.last()
        prep.edit(prep.data.draft.copy(total="14"),s);await { !prep.busy }
        assertEquals(130,frozen.maxGradeTenths)
    }
    @Test fun `draft validation follows edited distance laps and total and blocks publication during a race`() {
        val s=settings();unlock(s)
        val prep=keep(TeacherPreparationViewModel(app));await { prep.ready }
        val cls=prep.classes.first()
        val existingSessions=AndroidTeacherRepository.get(app).store.state.sessions
        val table=PerformanceTable(name="Fictional",distanceMeters=1000,sourceMaxTenths=70,
            tables=Sex.entries.associateWith { listOf(PerformanceThreshold(300000,70)) })
        prep.importTable(table,s);await { !prep.busy && prep.data.tables.isNotEmpty() }
        prep.edit(prep.data.draft.copy(classId=cls.id),s);await { !prep.busy }
        val blocked = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { RythmoTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TeacherPreparationForm(s,blocked.value,{}, {},prep)
        } } }
        compose.onNodeWithText("Nom affiché aux élèves").performTextReplacement("Prof Fiction")
        compose.onNodeWithText("Titre de la séance").performTextReplacement("Course test")
        compose.onNodeWithText("Distance (m)").performScrollTo().performTextReplacement("2000")
        compose.onNodeWithText("Vérifier ma séance").performScrollTo().performClick();await { prep.error!=null }
        assertNull(prep.preview);assertTrue(prep.error!!.contains("1000"))
        compose.onNodeWithText("Distance (m)").performScrollTo().performTextReplacement("1000")
        compose.onNodeWithText("Nombre de tours").performTextReplacement("?")
        compose.onNodeWithText("Note totale sur").performScrollTo().performTextReplacement("?")
        compose.onNodeWithText("Vérifier ma séance").performScrollTo().performClick();await { prep.error!=null }
        assertNull(prep.preview)
        compose.onNodeWithText("Nombre de tours").performScrollTo().performTextReplacement("6")
        compose.onNodeWithText("Note totale sur").performScrollTo().performTextReplacement("5")
        compose.onNodeWithText("Vérifier ma séance").performScrollTo().performClick();await { prep.error!=null }
        assertNull(prep.preview)
        compose.onNodeWithText("Note totale sur").performScrollTo().performTextReplacement("12")
        compose.onNodeWithText("Vérifier ma séance").performScrollTo().performClick();await { prep.preview!=null }
        assertEquals("Course test",prep.preview!!.title)
        compose.onNodeWithText("Modifier").performClick();assertNull(prep.preview)
        compose.runOnIdle { blocked.value=true }
        compose.onNodeWithText("Vérifier ma séance").assertIsNotEnabled()
        assertEquals(existingSessions,AndroidTeacherRepository.get(app).store.state.sessions)
    }
    @Test fun `roster preview rejects wrong columns then saves the explicitly mapped pupil`() {
        val s=settings();unlock(s)
        val prep=keep(TeacherPreparationViewModel(app));await { prep.ready }
        compose.setContent { RythmoTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TeacherPreparationForm(s,false,{}, {},prep)
        } } }
        compose.onNodeWithText("Importer une classe").performScrollTo().performClick()
        val file=File(app.filesDir,"reports/columns.csv").also { it.parentFile!!.mkdirs();it.writeText("Famille;Profil;Identité\nExemple;F;Alice") }
        prep.readDocument(FileProvider.getUriForFile(app,"${app.packageName}.files",file));await { prep.workbook!=null }
        compose.onNodeWithText("Nom de la classe").performTextInput("6e Fiction")
        compose.onNodeWithText("3e").performClick();compose.onNodeWithText("6e").performClick()
        compose.onNodeWithText("Vérifier l’import").performScrollTo().performClick()
        compose.onNodeWithText("Enregistrer").assertIsNotEnabled()
        compose.onNodeWithText("Colonne 2").performScrollTo().performClick();compose.onAllNodesWithText("Colonne 3").onLast().performClick()
        compose.onAllNodesWithText("Colonne 3").onLast().performScrollTo().performClick();compose.onNodeWithText("Colonne 2").performClick()
        compose.onNodeWithText("Première ligne d’élèves").performScrollTo().performTextReplacement("2")
        compose.onNodeWithText("Vérifier l’import").performScrollTo().performClick()
        compose.onNodeWithText("1 élèves").assertExists();compose.onNodeWithText("Enregistrer").performClick();await { prep.workbook==null }
        val imported=prep.classes.single { it.name=="6e Fiction" }
        assertEquals("6e",imported.level);assertEquals("Alice",imported.pupils.single().firstName);assertEquals(Sex.GIRL,imported.pupils.single().sex)
    }

}
