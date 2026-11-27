package com.bikecam.importer

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Lê uma atividade GPX, TCX ou FIT, detectando o formato pelo conteúdo (não pela extensão). */
object ActivityReader {
    /** Trilhas de ciclocomputador têm poucos MB; o teto evita ler por engano um arquivo enorme. */
    const val MAX_BYTES = 64 * 1024 * 1024

    fun read(input: InputStream): ActivityData {
        val bytes = readLimited(input)
        val format = detect(bytes) ?: throw IOException("Formato de trilha não reconhecido (use GPX, TCX ou FIT)")
        val bom = bomLength(bytes)
        val samples = ByteArrayInputStream(bytes, bom, bytes.size - bom).use {
            when (format) {
                ActivityFormat.GPX -> GpxReader.read(it)
                ActivityFormat.TCX -> TcxReader.read(it)
                ActivityFormat.FIT -> FitReader.read(it)
            }
        }
        if (samples.isEmpty()) throw IOException("A trilha não tem pontos com hora")
        return ActivityData(format, samples)
    }

    fun detect(bytes: ByteArray): ActivityFormat? {
        // Cabeçalho FIT: tamanho (12 ou 14), versões, tamanho dos dados e ".FIT" nos bytes 8..11.
        if (bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT") return ActivityFormat.FIT
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).lowercase()
        return when {
            "<trainingcenterdatabase" in head -> ActivityFormat.TCX
            "<gpx" in head -> ActivityFormat.GPX
            else -> null
        }
    }

    private fun bomLength(b: ByteArray) =
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0

    private fun readLimited(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw IOException("Arquivo de trilha grande demais")
        }
        return out.toByteArray()
    }
}
