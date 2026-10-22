package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Test

class UnreferencedAttachmentsTest {
    @Test fun findsNamesInUrlsPathsAndEscapedJson() {
        val remaining = hashSetOf(
            "chat-022d1576-7b43-475e-b46b-5c1d-0.jpg",
            "4f1c9d2e-aaaa.png",
            "c3d4.webp",
            "unused-1.jpg",
        )
        val text = """
            {"url":"file:///data/user/0/app/files/upload/chat-022d1576-7b43-475e-b46b-5c1d-0.jpg"}
            {"url":"file:\/\/\/data\/files\/upload\/4f1c9d2e-aaaa.png"}
            /data/user/0/app/files/upload/c3d4.webp
        """.trimIndent()

        removeReferencedFileNames(text, remaining)

        assertEquals(setOf("unused-1.jpg"), remaining)
    }

    @Test fun partialNameDoesNotCountAsReference() {
        val remaining = hashSetOf("abc.jpg")
        removeReferencedFileNames("upload/xabc.jpg and abc.jpgx", remaining)
        assertEquals(setOf("abc.jpg"), remaining)
    }
}
