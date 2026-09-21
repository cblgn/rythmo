package fr.rythmo

import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.OutputStream

/** Recording print backend for layout assertions. Real PDF encoding is tested by instrumentation. */
@Implements(PdfDocument::class)
class RecordingPdfDocument {
    private val pages = mutableListOf<PdfDocument.Page>()
    private var current: PdfDocument.Page? = null
    @Implementation fun __constructor__() { last = this }
    @Implementation fun startPage(info: PdfDocument.PageInfo): PdfDocument.Page {
        check(current == null)
        return ReflectionHelpers.callConstructor(PdfDocument.Page::class.java,
            ClassParameter.from(Canvas::class.java, Canvas()), ClassParameter.from(PdfDocument.PageInfo::class.java, info))
            .also { current = it }
    }
    @Implementation fun finishPage(page: PdfDocument.Page) { check(page === current); pages.add(page); current = null }
    @Implementation fun writeTo(output: OutputStream) { check(current == null); output.write(texts().joinToString("\n").toByteArray()) }
    @Implementation fun close() { check(current == null) }
    fun texts() = pages.flatMap { page -> shadowOf(page.canvas).let { canvas -> (0 until canvas.textHistoryCount).map { canvas.getDrawnTextEvent(it).text } } }
    val pageCount get() = pages.size
    companion object { lateinit var last: RecordingPdfDocument }
}
