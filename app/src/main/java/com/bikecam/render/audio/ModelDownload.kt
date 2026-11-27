package com.bikecam.render.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Baixa o modelo de áudio (publicado como anexo de uma release do repositório do BikeCam) para a
 * pasta do app. Continua de onde parou se a conexão cair (arquivo `.part`) e confere o SHA-256 antes
 * de liberar o modelo. Roda num escopo do processo: sair da tela não interrompe.
 */
object ModelDownload {
    sealed interface State {
        data object Idle : State
        data class Running(val bytes: Long, val total: Long) : State {
            val fraction: Float get() = bytes.toFloat() / total
        }
        data object Done : State
        data class Failed(val message: String) : State
    }

    const val URL_MODEL = "https://github.com/rwagnervm/BikeCam/releases/download/modelo-audio-v1/mossformer2_se_48k.onnx"
    const val SIZE = 119_937_536L
    private const val SHA256 = "6fe74d6dacecea29dbbb20894f19c88386cfd4eb76aa941ea38c6f7fb061adf3"
    private const val TAG = "BikeCam.Audio"
    private const val BUFFER = 1 shl 16

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = State.Running(0, SIZE)
            _state.value = try {
                download(app)
                State.Done
            } catch (e: IOException) {
                Log.w(TAG, "falha ao baixar o modelo", e)
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    private suspend fun download(context: Context) {
        val target = MossFormerModel.file(context)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        if (part.length() > SIZE) part.delete()

        // O SHA-256 cobre o arquivo inteiro: o que já foi baixado entra primeiro.
        val digest = MessageDigest.getInstance("SHA-256")
        if (part.exists()) part.inputStream().use { it.feed(digest) }
        var have = part.length()

        if (have < SIZE) {
            val conn = URL(URL_MODEL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "BikeCam (Android)")
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            try {
                when (conn.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> Unit
                    HttpURLConnection.HTTP_OK -> if (have > 0) {
                        // Servidor ignorou o Range: recomeça do zero.
                        digest.reset()
                        have = 0
                        part.delete()
                    }
                    else -> throw IOException("servidor respondeu ${conn.responseCode}")
                }
                conn.inputStream.use { input ->
                    FileOutputStream(part, have > 0).use { out ->
                        val buf = ByteArray(BUFFER)
                        var lastReport = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            have += n
                            if (have - lastReport >= 1 shl 20) {
                                lastReport = have
                                _state.value = State.Running(have, SIZE)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val size = part.length()
        if (size != SIZE || hash != SHA256) {
            part.delete()
            throw IOException("arquivo baixado não confere ($size bytes); tente de novo")
        }
        if (!part.renameTo(target)) throw IOException("não foi possível salvar o modelo")
        Log.i(TAG, "modelo baixado: ${target.length()} bytes")
    }

    private fun java.io.InputStream.feed(digest: MessageDigest) {
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = read(buf)
            if (n < 0) return
            digest.update(buf, 0, n)
        }
    }
}
