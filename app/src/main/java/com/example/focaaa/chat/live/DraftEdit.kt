package com.example.focaaa.chat.live

/** One composer text change, including the text removed by deletion or IME replacement. */
data class DraftEdit(
    val sequence: Long,
    val atMillis: Long,
    val start: Int,
    val removed: String,
    val inserted: String
) {
    fun applyTo(before: String): String {
        require(start in 0..before.length && start + removed.length <= before.length)
        require(before.regionMatches(start, removed, 0, removed.length)) {
            "A live draft edit does not match the previous text."
        }
        val result = before.replaceRange(start, start + removed.length, inserted)
        require(result.length <= MAX_DRAFT_CHARS) { "A live draft is too long." }
        return result
    }

    companion object {
        const val MAX_DRAFT_CHARS = 4_000

        fun between(before: String, after: String, sequence: Long, atMillis: Long): DraftEdit {
            require(sequence > 0 && atMillis > 0 && before != after)
            require(before.length <= MAX_DRAFT_CHARS && after.length <= MAX_DRAFT_CHARS)
            var prefix = 0
            while (prefix < before.length && prefix < after.length && before[prefix] == after[prefix]) prefix++
            var suffix = 0
            while (suffix < before.length - prefix && suffix < after.length - prefix &&
                before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
            return DraftEdit(sequence, atMillis, prefix,
                before.substring(prefix, before.length - suffix),
                after.substring(prefix, after.length - suffix))
        }
    }
}
