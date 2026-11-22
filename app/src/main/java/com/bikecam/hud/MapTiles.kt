package com.bikecam.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fonte de tiles 256x256 do mapa. */
interface TileSource {
    /** Nunca bloqueia: devolve o tile se estiver em memória; senão null e busca em segundo plano. */
    fun tile(z: Int, x: Int, y: Int): Bitmap?

    /** Só consulta a memória, sem disparar download (usado para o fallback de zoom menor). */
    fun peek(z: Int, x: Int, y: Int): Bitmap? = null

    /** Bloqueia até [timeoutMs] esperando o tile (disco ou rede). Só para fora das threads de vídeo/UI. */
    fun await(z: Int, x: Int, y: Int, timeoutMs: Long): Bitmap? = tile(z, x, y)
}

/**
 * Tiles do OpenStreetMap com cache em memória e em disco. Em viagem sem internet o mapa usa o que
 * já estiver em cache e, no pior caso, mostra só a trilha sobre fundo escuro.
 *
 * Segue a política de uso do tile.openstreetmap.org: User-Agent identificando o app, cache local
 * e nenhuma pré-carga em massa (só baixa os tiles que aparecem no mini-mapa).
 */
class OsmTileCache(context: Context) : TileSource {
    private val dir = File(context.cacheDir, "tiles")
    private val memory = LruCache<Long, Bitmap>(MEMORY_TILES)
    /** Tiles em carregamento; o latch é liberado quando a carga termina (com ou sem sucesso). */
    private val pending = ConcurrentHashMap<Long, CountDownLatch>()
    private val failedAt = ConcurrentHashMap<Long, Long>()
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "BikeCam-Tiles").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /** Desligado: não desenha fundo de mapa (só a trilha) e não usa a rede. */
    @Volatile var enabled: Boolean = true

    init {
        executor.execute { trimDisk() }
    }

    override fun tile(z: Int, x: Int, y: Int): Bitmap? {
        if (!enabled) return null
        val n = 1 shl z
        if (y !in 0 until n) return null
        val wx = ((x % n) + n) % n
        val key = key(z, wx, y)
        memory.get(key)?.let { return it }
        request(key, z, wx, y)
        return null
    }

    override fun peek(z: Int, x: Int, y: Int): Bitmap? {
        if (!enabled) return null
        val n = 1 shl z
        if (y !in 0 until n) return null
        return memory.get(key(z, ((x % n) + n) % n, y))
    }

    override fun await(z: Int, x: Int, y: Int, timeoutMs: Long): Bitmap? {
        if (!enabled) return null
        val n = 1 shl z
        if (y !in 0 until n) return null
        val wx = ((x % n) + n) % n
        val key = key(z, wx, y)
        memory.get(key)?.let { return it }
        val latch = request(key, z, wx, y) ?: return null
        if (timeoutMs > 0) latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return memory.get(key)
    }

    /** Enfileira a carga (uma vez por tile) e devolve o latch; null se falhou há pouco. */
    private fun request(key: Long, z: Int, x: Int, y: Int): CountDownLatch? {
        pending[key]?.let { return it }
        val failed = failedAt[key]
        if (failed != null && SystemClock.elapsedRealtime() - failed < RETRY_MS) return null
        val latch = CountDownLatch(1)
        val existing = pending.putIfAbsent(key, latch)
        if (existing != null) return existing
        executor.execute { load(key, z, x, y) }
        return latch
    }

    private fun key(z: Int, x: Int, y: Int) = (z.toLong() shl 50) or (x.toLong() shl 25) or y.toLong()

    private fun load(key: Long, z: Int, x: Int, y: Int) {
        try {
            val file = File(dir, "$z/$x/$y.png")
            var bmp = if (file.exists()) decode(file) else null
            if (bmp == null) {
                bmp = if (download(z, x, y, file)) decode(file) else null
                if (bmp == null) failedAt[key] = SystemClock.elapsedRealtime() else failedAt.remove(key)
            }
            if (bmp != null) memory.put(key, bmp)
        } catch (e: Exception) {
            Log.w(TAG, "tile $z/$x/$y: ${e.message}")
            failedAt[key] = SystemClock.elapsedRealtime()
        } finally {
            pending.remove(key)?.countDown()
        }
    }

    private fun decode(file: File): Bitmap? =
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })

    private fun download(z: Int, x: Int, y: Int, target: File): Boolean {
        val conn = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            return tmp.renameTo(target)
        } finally {
            conn.disconnect()
        }
    }

    /** Mantém o cache em disco abaixo de [DISK_LIMIT_BYTES], apagando os tiles mais antigos. */
    private fun trimDisk() {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= DISK_LIMIT_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= DISK_LIMIT_BYTES * 3 / 4) break
        }
    }

    private companion object {
        const val TAG = "BikeCam.Tiles"
        const val USER_AGENT = "BikeCam/0.2 (Android cycling dashcam; personal use)"
        const val MEMORY_TILES = 64 // ~8 MB em RGB_565
        const val RETRY_MS = 30_000L
        const val DISK_LIMIT_BYTES = 150L * 1024 * 1024
    }
}
