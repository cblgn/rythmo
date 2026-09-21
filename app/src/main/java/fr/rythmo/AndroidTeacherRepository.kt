package fr.rythmo

import android.content.Context
import fr.rythmo.session.TeacherPreparation
import fr.rythmo.session.JsonFile
import fr.rythmo.sync.TeacherStore
import java.io.File

/** One durable store per Android process, shared by preparation and all transports. */
class AndroidTeacherRepository private constructor(context: Context) {
    val store = TeacherStore(File(context.filesDir, "teacher"))
    private val file = JsonFile(File(context.filesDir, "teacher/preparation.json"), TeacherPreparation.serializer()) { TeacherPreparation() }
    @Volatile var preparation = file.read(); private set
    @Synchronized fun save(value: TeacherPreparation) { file.write(value); preparation = value }
    companion object {
        @Volatile private var instance: AndroidTeacherRepository? = null
        fun get(context: Context): AndroidTeacherRepository = instance ?: synchronized(this) {
            instance ?: AndroidTeacherRepository(context.applicationContext).also { instance = it }
        }
    }
}
