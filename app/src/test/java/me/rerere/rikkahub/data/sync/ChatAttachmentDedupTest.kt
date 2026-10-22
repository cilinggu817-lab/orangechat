package me.rerere.rikkahub.data.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatAttachmentDedupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun reusesByteIdenticalUploadAndIgnoresSameSizeDifferentContent() {
        val upload = temporary.newFolder("upload")
        val same = File(upload, "a.jpg").apply { writeText("picture-1") }
        File(upload, "b.jpg").writeText("picture-2") // same length, different bytes
        File(upload, ".tmp.jpg").writeText("picture-1") // hidden temp files never match
        val staged = temporary.newFolder("staged")
        val dedup = ChatAttachmentDedup(upload)

        assertEquals(same, dedup.findExisting(File(staged, "0.jpg").apply { writeText("picture-1") }))
        assertNull(dedup.findExisting(File(staged, "1.jpg").apply { writeText("picture-3") }))
        assertNull(dedup.findExisting(File(staged, "2.jpg").apply { writeText("other size") }))
    }

    @Test fun registeredCopyIsReusedByLaterDuplicateInSameRestore() {
        val upload = temporary.newFolder("upload")
        val staged = temporary.newFolder("staged")
        val dedup = ChatAttachmentDedup(upload)
        val first = File(staged, "0.png").apply { writeText("dup") }
        assertNull(dedup.findExisting(first))
        val copy = File(upload, "chat-x-0.png").apply { writeText("dup") }
        dedup.register(copy)

        assertEquals(copy, dedup.findExisting(File(staged, "1.png").apply { writeText("dup") }))
    }
}
