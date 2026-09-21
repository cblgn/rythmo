package fr.rythmo

import androidx.core.content.FileProvider
import org.robolectric.util.ReflectionHelpers

/** Robolectric changes the app directory between tests while these process caches survive. */
fun resetAndroidFixtures() {
    ReflectionHelpers.getStaticField<MutableMap<String, *>>(FileProvider::class.java, "sCache").clear()
    ReflectionHelpers.setStaticField(AndroidTeacherRepository::class.java, "instance", null)
}
