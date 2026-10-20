package com.maya.ai.overlay

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and stores the offline Vosk model (about 40 MB, Apache 2.0) once, inside the app's
 * private storage. After that, wake-word detection needs no internet.
 */
object WakeModel {

    sealed interface State {
        data object Missing : State
        data class Downloading(val percent: Int, val phase: String) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val MIN_ZIP_BYTES = 10L * 1024 * 1024

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** The unpacked model folder, or null if it is not (completely) there. */
    fun modelDir(context: Context): File? {
        val dir = File(context.filesDir, "vosk/model")
        return dir.takeIf { File(it, "am").isDirectory && File(it, "conf").isDirectory }
    }

    /** Re-reads the real state from disk (does not touch a running download). */
    fun refresh(context: Context) {
        if (_state.value is State.Downloading) return
        _state.value = if (modelDir(context) != null) State.Ready else State.Missing
    }

    fun startDownload(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch { download(app) }
    }

    fun cancelDownload(context: Context) {
        job?.cancel()
        job = null
        _state.value = if (modelDir(context) != null) State.Ready else State.Missing
    }

    fun delete(context: Context) {
        job?.cancel()
        job = null
        File(context.filesDir, "vosk").deleteRecursively()
        _state.value = State.Missing
    }

    private suspend fun download(app: Context) {
        val root = File(app.filesDir, "vosk").apply { mkdirs() }
        val zip = File(app.cacheDir, "vosk-model.zip")
        val tmp = File(root, "unpack")
        try {
            _state.value = State.Downloading(0, "Download ho raha hai")
            fetch(zip)
            _state.value = State.Downloading(100, "Unpack ho raha hai")
            tmp.deleteRecursively()
            tmp.mkdirs()
            unzip(zip, tmp)
            val found = tmp.walkTopDown().maxDepth(3).firstOrNull {
                it.isDirectory && File(it, "am").isDirectory && File(it, "conf").isDirectory
            } ?: throw IOException("Model ki files zip mein nahi mili.")
            val target = File(root, "model")
            target.deleteRecursively()
            if (!found.renameTo(target)) throw IOException("Model save nahi ho paya.")
            _state.value = State.Ready
        } catch (e: CancellationException) {
            _state.value = if (modelDir(app) != null) State.Ready else State.Missing
            throw e
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "Download fail ho gaya.")
        } finally {
            zip.delete()
            tmp.deleteRecursively()
        }
    }

    private suspend fun fetch(dest: File) = withContext(Dispatchers.IO) {
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        val thisJob = coroutineContext[Job]
        val handle = thisJob?.invokeOnCompletion { if (it is CancellationException) conn.disconnect() }
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Download fail hua (HTTP $code).")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            _state.value = State.Downloading(
                                (read * 100 / total).toInt().coerceIn(0, 99), "Download ho raha hai"
                            )
                        }
                        ensureActive()
                    }
                }
            }
            if (dest.length() < MIN_ZIP_BYTES) throw IOException("Download adhura laga. Dobara try karein.")
        } catch (e: SocketTimeoutException) {
            throw IOException("Download time out ho gaya. Internet check karein.")
        } catch (e: IOException) {
            if (thisJob?.isActive == false) throw CancellationException("cancelled")
            throw e
        } finally {
            handle?.dispose()
            conn.disconnect()
        }
    }

    private fun unzip(zip: File, dest: File) {
        val destPath = dest.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(destPath)) throw IOException("Zip file sahi nahi hai.")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }
}
