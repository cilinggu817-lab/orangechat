package me.rerere.rikkahub.data.sync

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Avatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupAssetsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rebasesUploadedAvatarsAndBackgroundsAcrossApplicationIds() {
        val filesDir = temporary.newFolder("current-files")
        val assistant = Settings().assistants.first().copy(
            avatar = Avatar.Image("file:///data/user/0/old.package/files/upload/assistant.png"),
            background = "file:///data/user/0/old.package/files/upload/background.png",
        )
        val settings = Settings(
            displaySetting = Settings().displaySetting.copy(
                userAvatar = Avatar.Image("file:///data/user/0/old.package/files/upload/user.png"),
            ),
            assistants = listOf(assistant),
        )

        val restored = settings.rebaseRestoredAssetPaths(
            filesDir,
            listOf(
                "files/upload/user.png",
                "files/upload/assistant.png",
                "files/upload/background.png",
            ),
        )

        assertEquals(
            File(filesDir, "upload/user.png").toURI().toString(),
            (restored.displaySetting.userAvatar as Avatar.Image).url,
        )
        assertEquals(
            File(filesDir, "upload/assistant.png").toURI().toString(),
            (restored.assistants.single().avatar as Avatar.Image).url,
        )
        assertEquals(
            File(filesDir, "upload/background.png").toURI().toString(),
            restored.assistants.single().background,
        )
    }

    @Test fun rebasesOnlyAppearanceFilesThatWereExtracted() {
        val filesDir = temporary.newFolder("appearance-files")
        val oldRoot = "/data/user/0/old.package/files"
        val settings = Settings().copy(
            displaySetting = Settings().displaySetting.copy(
                settingsBackgroundPath = "$oldRoot/settings_backgrounds/settings.png",
                drawerBackgroundPath = "$oldRoot/drawer_backgrounds/drawer.png",
                customFontPath = "$oldRoot/custom_fonts/missing.ttf",
            ),
        )

        val restored = settings.rebaseRestoredAssetPaths(
            filesDir,
            listOf(
                "files/settings_backgrounds/settings.png",
                "files/drawer_backgrounds/drawer.png",
            ),
        )

        assertEquals(
            File(filesDir, "settings_backgrounds/settings.png").absolutePath,
            restored.displaySetting.settingsBackgroundPath,
        )
        assertEquals(
            File(filesDir, "drawer_backgrounds/drawer.png").absolutePath,
            restored.displaySetting.drawerBackgroundPath,
        )
        assertEquals("$oldRoot/custom_fonts/missing.ttf", restored.displaySetting.customFontPath)
    }

    @Test fun remoteImagesAreNotRewritten() {
        val filesDir = temporary.newFolder("remote-files")
        val remote = "https://example.com/avatar.png"
        val settings = Settings().copy(
            displaySetting = Settings().displaySetting.copy(userAvatar = Avatar.Image(remote)),
        )

        val restored = settings.rebaseRestoredAssetPaths(filesDir, listOf("files/upload/avatar.png"))

        assertEquals(remote, (restored.displaySetting.userAvatar as Avatar.Image).url)
    }

    @Test fun stagesEveryAppearanceDirectory() = runBlocking {
        val archive = File(temporary.root, "appearance.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            BACKUP_APPEARANCE_DIRECTORIES.forEach { directory ->
                zip.putNextEntry(ZipEntry("$directory/example.bin"))
                zip.write(directory.toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("settings_backgrounds_extra/ignored.bin"))
            zip.write("ignored".toByteArray())
            zip.closeEntry()
        }
        val root = temporary.newFolder("restore-root")
        val transaction = BackupRestoreTransaction(root).apply { begin() }

        val staged = stageBackupArchive(archive, transaction, true, false, false)

        BACKUP_APPEARANCE_DIRECTORIES.forEach { directory ->
            val path = "files/$directory/example.bin"
            assertTrue(path in staged.files)
            assertEquals(directory, transaction.stagePath(path).readText())
        }
        assertFalse("files/settings_backgrounds_extra/ignored.bin" in staged.files)
        transaction.discardUnprepared()
    }
}
