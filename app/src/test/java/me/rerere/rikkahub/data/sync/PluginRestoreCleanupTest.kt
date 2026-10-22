package me.rerere.rikkahub.data.sync

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginRestoreCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archiveOf(vararg entries: Pair<String, String>): File {
        val archive = File(temporary.root, "backup-${System.nanoTime()}.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }

    @Test fun olderPluginBackupRemovesFilesAddedByNewerVersion() = runBlocking {
        val root = temporary.newFolder("root")
        val plugin = File(root, "files/plugins/demo").apply { mkdirs() }
        File(plugin, "main.js").writeText("new main")
        File(plugin, ".integrity").writeText("new checksum")
        File(plugin, "ui").mkdirs()
        val added = File(plugin, "ui/references.js").apply { writeText("only in new version") }
        val untouched = File(root, "files/plugins/other/main.js").apply { parentFile!!.mkdirs(); writeText("other") }

        val archive = archiveOf(
            "plugins/demo/main.js" to "old main",
            "plugins/demo/.integrity" to "old checksum",
        )
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        val staged = stageBackupArchive(archive, transaction, true, false, true)
        val stale = stalePluginFiles(root, staged.files)
        assertEquals(listOf("files/plugins/demo/ui/references.js"), stale)

        transaction.prepare(staged.files + stale, emptyList(), emptyList())
        transaction.installFiles()
        transaction.finish(hasDatabase = false)

        assertEquals("old main", File(plugin, "main.js").readText())
        assertEquals("old checksum", File(plugin, ".integrity").readText())
        assertFalse(added.exists())
        // Plugins absent from the backup are not touched.
        assertEquals("other", untouched.readText())
    }

    @Test fun rollbackRestoresRemovedPluginFiles() = runBlocking {
        val root = temporary.newFolder("root")
        val plugin = File(root, "files/plugins/demo").apply { mkdirs() }
        File(plugin, "main.js").writeText("new main")
        val added = File(plugin, "extra.js").apply { writeText("extra") }

        val archive = archiveOf("plugins/demo/main.js" to "old main")
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        val staged = stageBackupArchive(archive, transaction, true, false, true)
        val stale = stalePluginFiles(root, staged.files)
        transaction.prepare(staged.files + stale, emptyList(), emptyList())
        transaction.installFiles()
        transaction.rollbackFiles()

        assertEquals("new main", File(plugin, "main.js").readText())
        assertTrue(added.exists())
        assertEquals("extra", added.readText())
    }

    @Test fun backupWithoutPluginsRemovesNothing() = runBlocking {
        val root = temporary.newFolder("root")
        File(root, "files/plugins/demo/main.js").apply { parentFile!!.mkdirs(); writeText("live") }
        val archive = archiveOf("upload/a.txt" to "a")
        val transaction = BackupRestoreTransaction(root).apply { begin() }
        val staged = stageBackupArchive(archive, transaction, true, false, false)
        assertEquals(emptyList<String>(), stalePluginFiles(root, staged.files))
        transaction.discardUnprepared()
    }
}
