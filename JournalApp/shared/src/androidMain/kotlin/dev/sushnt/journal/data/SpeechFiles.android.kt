package dev.sushnt.journal.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

actual fun fileLength(path: String): Long = File(path).let { if (it.isFile) it.length() else -1L }

actual fun downloadToFile(url: String, path: String, onBytes: (Long) -> Unit) {
    val target = File(path)
    target.parentFile?.mkdirs()
    val part = File("$path.part")
    var address = url
    // Hugging Face and GitHub answer with a redirect to their file servers: follow it.
    repeat(6) {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "Journal-app")
        try {
            val code = conn.responseCode
            if (code in 300..399) {
                address = URL(URL(address), conn.getHeaderField("Location") ?: throw IOException("Redirect without a location")).toString()
                return@repeat
            }
            if (code != 200) throw IOException("HTTP $code for $url")
            var total = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        total += n
                        if (total - lastReport > 256 * 1024) {
                            lastReport = total
                            onBytes(total)
                        }
                    }
                }
            }
            onBytes(total)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Couldn't save $path")
            return
        } finally {
            conn.disconnect()
        }
    }
    throw IOException("Too many redirects for $url")
}
