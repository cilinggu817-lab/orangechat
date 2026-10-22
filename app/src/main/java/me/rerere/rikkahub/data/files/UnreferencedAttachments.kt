package me.rerere.rikkahub.data.files

/**
 * Removes from [remaining] every candidate file name that appears in [text] as a whole token.
 *
 * Upload names are UUID-like tokens (letters, digits, '-', '_', '.'), so the text is split into
 * runs of those characters once and each run is looked up in a hash set. This is linear in the
 * text size instead of (files × rows) substring searches, and still catches names inside
 * file:// URLs, absolute paths, escaped JSON ("\/"), tool outputs and settings. A spurious match
 * only keeps a file; it can never cause a deletion.
 */
internal fun removeReferencedFileNames(text: String, remaining: MutableSet<String>) {
    if (remaining.isEmpty() || text.isEmpty()) return
    var start = -1
    for (i in 0..text.length) {
        val c = if (i < text.length) text[i] else ' '
        if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') {
            if (start < 0) start = i
        } else if (start >= 0) {
            remaining.remove(text.substring(start, i))
            start = -1
            if (remaining.isEmpty()) return
        }
    }
}
