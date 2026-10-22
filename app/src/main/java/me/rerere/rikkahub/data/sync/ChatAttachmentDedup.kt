package me.rerere.rikkahub.data.sync

import java.io.File
import java.security.MessageDigest

/**
 * Finds an existing upload file with byte-identical content, so restoring a chat backup on the
 * same device reuses attachments instead of writing another full copy of every file.
 *
 * Files are grouped by size first; SHA-256 is only computed for same-size candidates, and each
 * digest is computed at most once per restore. Hidden/temporary files are never matched.
 */
internal class ChatAttachmentDedup(private val directory: File) {
    private val bySize: MutableMap<Long, MutableList<File>> by lazy {
        val map = hashMapOf<Long, MutableList<File>>()
        directory.listFiles()?.forEach { file ->
            if (file.isFile && !file.name.startsWith(".") && file.length() > 0) {
                map.getOrPut(file.length()) { mutableListOf() }.add(file)
            }
        }
        map
    }
    private val digests = hashMapOf<String, String>()

    /** Returns an existing file with identical bytes, or null when the source must be copied. */
    fun findExisting(source: File): File? {
        val size = source.length()
        if (size <= 0) return null
        val candidates = bySize[size] ?: return null
        val wanted = sha256(source)
        return candidates.firstOrNull { candidate ->
            candidate.isFile && candidate.length() == size &&
                digests.getOrPut(candidate.absolutePath) { sha256(candidate) } == wanted
        }
    }

    /** Make a freshly copied file matchable by later identical attachments in the same restore. */
    fun register(file: File) {
        if (!file.isFile || file.length() <= 0) return
        bySize.getOrPut(file.length()) { mutableListOf() }.add(file)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
