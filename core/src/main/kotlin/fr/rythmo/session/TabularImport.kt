package fr.rythmo.session

import kotlinx.serialization.Serializable
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.text.Normalizer
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.ext.DefaultHandler2

@Serializable data class ImportFile(val filename: String, val base64: String)
@Serializable data class ImportSheet(val name: String, val rows: List<List<String>>, val ignoredFormulas: Int = 0)
@Serializable data class ImportWorkbook(val sheets: List<ImportSheet>)

/** Reads cells only: no macros, formula execution, file extraction or external relationships. */
object TabularImport {
    fun read(file: ImportFile): ImportWorkbook {
        val bytes = Base64.getDecoder().decode(file.base64)
        require(bytes.size in 1..2_000_000) { "Choisissez un fichier de moins de 2 Mo." }
        return when (file.filename.substringAfterLast('.').lowercase()) {
            "csv" -> ImportWorkbook(listOf(ImportSheet("CSV", csv(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")))))
            "xlsx" -> xlsx(bytes)
            else -> error("Formats acceptés : CSV UTF-8 et XLSX." )
        }
    }
    internal fun csv(text: String): List<List<String>> {
        val separator = if (text.lineSequence().first().count { it == ';' } >= text.lineSequence().first().count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        fun field() { require(cell.length <= 1000) { "Cellule trop longue." }; row += cell.toString(); cell.clear() }
        fun line() { field(); require(row.size <= 50 && rows.size < 1000) { "Table trop grande (1000 lignes, 50 colonnes)." }; if (row.any(String::isNotBlank)) rows += row.toList(); row.clear() }
        while (index < text.length) {
            val ch = text[index++]
            when {
                ch == '"' && quoted && text.getOrNull(index) == '"' -> { cell.append('"'); index++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == separator -> field()
                !quoted && ch == '\n' -> line()
                !quoted && ch == '\r' -> { if (text.getOrNull(index) == '\n') index++; line() }
                else -> cell.append(ch)
            }
            require(cell.length <= 1000) { "Cellule trop longue." }
        }
        require(!quoted) { "Guillemets CSV non fermés." }
        if (cell.isNotEmpty() || row.isNotEmpty()) line()
        require(rows.isNotEmpty()) { "Table vide." }
        return rows
    }
    private class XmlNode(val name: String, val attributes: Map<String, String>) {
        val children = mutableListOf<XmlNode>()
        val text = StringBuilder()
        val textContent: String get() = text.toString() + children.joinToString("") { it.textContent }
        fun getAttribute(name: String): String = attributes[name].orEmpty()
        fun getAttributeNS(namespace: String, name: String): String = getAttribute("$namespace|$name")
        fun elements(name: String): List<XmlNode> = children.flatMap { child ->
            (if (child.name == name) listOf(child) else emptyList()) + child.elements(name)
        }
    }
    private fun xml(bytes: ByteArray): XmlNode {
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val stack = java.util.ArrayDeque<XmlNode>()
        var root: XmlNode? = null
        var nodes = 0
        val handler = object : DefaultHandler2() {
            override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("DTD is not supported") }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("External entities are not supported") }
            override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes) {
                require(++nodes <= 100_000 && stack.size < 64) { "XML trop complexe." }
                val values = mutableMapOf<String, String>()
                for (i in 0 until attributes.length) {
                    values[attributes.getQName(i)] = attributes.getValue(i)
                    values[attributes.getURI(i) + "|" + attributes.getLocalName(i)] = attributes.getValue(i)
                }
                val node = XmlNode(localName, values)
                if (stack.isEmpty()) root = node else stack.peekLast().children += node
                stack.addLast(node)
            }
            override fun characters(chars: CharArray, start: Int, length: Int) { stack.peekLast()?.text?.append(chars, start, length) }
            override fun endElement(uri: String?, localName: String?, qName: String?) { stack.removeLast() }
        }
        reader.contentHandler = handler
        reader.entityResolver = handler
        // Both Android Expat and the JVM SAX parser support this fail-closed DTD hook.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        try { reader.parse(InputSource(ByteArrayInputStream(bytes))) }
        catch (error: SAXException) { throw IllegalArgumentException("XML invalide : les DTD et entités externes sont interdites.", error) }
        return requireNotNull(root) { "Document XML vide." }
    }
    private fun xlsx(bytes: ByteArray): ImportWorkbook {
        val entries = mutableMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            while (entry != null) {
                require(++count <= 200) { "Classeur trop complexe." }
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= 8_000_000) { "Classeur décompressé trop volumineux." }
                    out.write(buffer, 0, read)
                }
                require(!entries.containsKey(entry.name)) { "Entrée XLSX dupliquée." }
                entries[entry.name] = out.toByteArray()
                entry = zip.nextEntry
            }
        }
        fun document(path: String) = xml(requireNotNull(entries[path]) { "Classeur XLSX incomplet." })
        val strings = entries["xl/sharedStrings.xml"]?.let { xml(it).elements("si").map { cell -> cell.elements("t").joinToString("") { it.textContent } } } ?: emptyList()
        val relationships = document("xl/_rels/workbook.xml.rels").elements("Relationship").filter { it.getAttribute("TargetMode") != "External" }
            .associate { it.getAttribute("Id") to it.getAttribute("Target") }
        val sheets = document("xl/workbook.xml").elements("sheet").map { sheet ->
            val target = requireNotNull(relationships[sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")])
            val path = if (target.startsWith('/')) target.drop(1) else "xl/$target"
            require(path.startsWith("xl/worksheets/") && !path.contains("..")) { "Feuille XLSX invalide." }
            var formulas = 0
            val rows = document(path).elements("row").map { row ->
                val values = mutableMapOf<Int, String>()
                row.elements("c").forEach { cell ->
                    if (cell.elements("f").isNotEmpty()) { formulas++; return@forEach }
                    val index = cell.getAttribute("r").takeWhile(Char::isLetter).fold(0) { acc, c -> acc * 26 + c.uppercaseChar().code - 'A'.code + 1 } - 1
                    require(index in 0..49) { "Une feuille ne peut pas dépasser 50 colonnes." }
                    val raw = cell.elements("v").firstOrNull()?.textContent.orEmpty()
                    val value = when (cell.getAttribute("t")) {
                        "s" -> strings.getOrNull(raw.toIntOrNull() ?: -1) ?: error("Texte XLSX invalide.")
                        "inlineStr" -> cell.elements("t").joinToString("") { it.textContent }
                        else -> raw
                    }
                    require(value.length <= 1000)
                    values[index] = value
                }
                List((values.keys.maxOrNull() ?: -1) + 1) { values[it].orEmpty() }
            }.filter { row -> row.any(String::isNotBlank) }
            require(rows.size <= 1000) { "Une feuille ne peut pas dépasser 1000 lignes." }
            ImportSheet(sheet.getAttribute("name"), rows, formulas)
        }
        require(sheets.isNotEmpty() && sheets.size <= 30)
        return ImportWorkbook(sheets)
    }
}

/** Match the explicit class first, then normalized full names; never merge ambiguous pupils. */
object ClassImport {
    private fun key(pupil: Pupil) = normalize(pupil.firstName) + "\u0000" + normalize(pupil.lastName)
    private fun normalize(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)
    fun reconcile(incoming: SchoolClass, previous: SchoolClass?): SchoolClass {
        require(incoming.name.isNotBlank() && incoming.name.length <= 100 && incoming.level in setOf("6e", "5e", "4e", "3e"))
        require(incoming.pupils.size in 1..30) { "Une classe contient de 1 à 30 élèves." }
        incoming.pupils.forEach(Pupil::validate)
        require(incoming.pupils.map(::key).distinct().size == incoming.pupils.size) { "Doublon de prénom et nom : distinguez les élèves avant import." }
        val pupils = incoming.pupils.map { pupil ->
            val matches = previous?.pupils.orEmpty().filter { key(it) == key(pupil) }
            require(matches.size <= 1) { "Identité ambiguë dans la classe existante." }
            pupil.copy(id = matches.singleOrNull()?.id ?: newId())
        }
        return incoming.copy(id = previous?.id ?: newId(), pupils = pupils)
    }
}
