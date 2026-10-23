package me.rerere.rikkahub.data.sync

import java.io.File
import java.net.URI
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Avatar

/** App-owned file directories that belong in a full files backup. */
internal val BACKUP_FILE_DIRECTORIES = listOf(
    FileFolders.UPLOAD,
    FileFolders.SKILLS,
    "settings_backgrounds",
    "input_backgrounds",
    "drawer_backgrounds",
    "avatar_frames",
    "bubble_backgrounds",
    "custom_fonts",
)

internal val BACKUP_APPEARANCE_DIRECTORIES = BACKUP_FILE_DIRECTORIES.drop(2)

internal fun isBackupFileEntry(name: String): Boolean =
    BACKUP_FILE_DIRECTORIES.any { directory -> name.startsWith("$directory/") }

/**
 * Backup settings contain app-private absolute paths. Repoint paths whose files were actually
 * extracted so restores keep working across application IDs (for example, preview builds). Remote
 * URLs and paths whose files were not backed up are left untouched.
 */
internal fun Settings.rebaseRestoredAssetPaths(
    filesDir: File,
    stagedFiles: Collection<String>,
): Settings {
    val staged = stagedFiles.toHashSet()

    fun String.rebase(directory: String): String {
        if (isBlank()) return this
        val localPath = when {
            startsWith("file:", ignoreCase = true) -> runCatching { URI(this).path }.getOrNull()
            startsWith(File.separator) -> this
            else -> null
        } ?: return this
        val fileName = File(localPath).name.takeIf { it.isNotBlank() } ?: return this
        if ("files/$directory/$fileName" !in staged) return this
        val target = File(filesDir, "$directory/$fileName")
        return if (startsWith("file:", ignoreCase = true)) target.toURI().toString() else target.absolutePath
    }

    fun Avatar.rebaseUpload(): Avatar = when (this) {
        is Avatar.Image -> copy(url = url.rebase(FileFolders.UPLOAD))
        else -> this
    }

    return copy(
        displaySetting = displaySetting.copy(
            userAvatar = displaySetting.userAvatar.rebaseUpload(),
            customFontPath = displaySetting.customFontPath.rebase("custom_fonts"),
            settingsBackgroundPath = displaySetting.settingsBackgroundPath.rebase("settings_backgrounds"),
            inputBackgroundPath = displaySetting.inputBackgroundPath.rebase("input_backgrounds"),
            userAvatarFramePath = displaySetting.userAvatarFramePath.rebase("avatar_frames"),
            aiAvatarFramePath = displaySetting.aiAvatarFramePath.rebase("avatar_frames"),
            drawerBackgroundPath = displaySetting.drawerBackgroundPath.rebase("drawer_backgrounds"),
            userBubbleImagePath = displaySetting.userBubbleImagePath.rebase("bubble_backgrounds"),
            assistantBubbleImagePath = displaySetting.assistantBubbleImagePath.rebase("bubble_backgrounds"),
        ),
        assistants = assistants.map { assistant ->
            assistant.copy(
                avatar = assistant.avatar.rebaseUpload(),
                background = assistant.background?.rebase(FileFolders.UPLOAD),
            )
        },
    )
}
