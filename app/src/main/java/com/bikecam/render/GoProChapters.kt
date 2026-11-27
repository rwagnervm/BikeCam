package com.bikecam.render

/**
 * A GoPro divide gravações longas em capítulos de ~4 GB com o mesmo número de arquivo:
 * - HERO6 em diante: `GX010127.MP4`, `GX020127.MP4`… (`G` + codec + capítulo + número);
 * - até a HERO5: `GOPR0127.MP4`, depois `GP010127.MP4`, `GP020127.MP4`…
 * O Quik pode acrescentar um sufixo (`GX010127_1795371688674.MP4`), que é ignorado.
 */
object GoProChapters {
    private val MODERN = Regex("""^G([A-Z])(\d{2})(\d{4})""", RegexOption.IGNORE_CASE)
    private val LEGACY_FIRST = Regex("""^GOPR(\d{4})""", RegexOption.IGNORE_CASE)

    data class Chapter(val group: String, val index: Int)

    fun parse(fileName: String): Chapter? {
        LEGACY_FIRST.find(fileName)?.let { return Chapter("GP" + it.groupValues[1], 0) }
        val m = MODERN.find(fileName) ?: return null
        val codec = m.groupValues[1].uppercase()
        return Chapter("G" + codec + m.groupValues[3], m.groupValues[2].toInt())
    }

    /**
     * Agrupa os nomes por gravação, com os capítulos em ordem. Arquivos que não são da GoPro ficam
     * sozinhos. Os grupos seguem a ordem em que apareceram na lista.
     */
    fun group(fileNames: List<String>): List<List<String>> {
        val groups = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
        fileNames.forEachIndexed { i, name ->
            val c = parse(name)
            val key = c?.group?.uppercase() ?: "\u0000$i"
            groups.getOrPut(key) { ArrayList() }.add((c?.index ?: 0) to name)
        }
        return groups.values.map { list -> list.sortedBy { it.first }.map { it.second } }
    }
}
