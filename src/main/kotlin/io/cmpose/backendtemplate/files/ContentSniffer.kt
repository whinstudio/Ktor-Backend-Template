package io.cmpose.backendtemplate.files

// Signatures as ISO-8859-1 text, so each char is exactly one byte.
private val JPEG = signature("ÿØÿ")
private val PNG = signature("\u0089PNG\r\n\u001A\n")
private val RIFF = signature("RIFF")
private val WEBP = signature("WEBP")
private val PDF = signature("%PDF-")
private const val WEBP_TAG_OFFSET = 8

private fun signature(text: String) = text.toByteArray(Charsets.ISO_8859_1)

private fun ByteArray.hasAt(offset: Int, expected: ByteArray) =
    size >= offset + expected.size && expected.indices.all { this[offset + it] == expected[it] }

/** Tells an allowed file type from the content bytes; file names and `Content-Type` headers are never trusted. */
object ContentSniffer {
    /** `image/jpeg`, `image/png`, `image/webp` or `application/pdf`; null for anything else. */
    fun detect(bytes: ByteArray): String? = when {
        bytes.hasAt(0, JPEG) -> "image/jpeg"
        bytes.hasAt(0, PNG) -> "image/png"
        bytes.hasAt(0, RIFF) && bytes.hasAt(WEBP_TAG_OFFSET, WEBP) -> "image/webp"
        bytes.hasAt(0, PDF) -> "application/pdf"
        else -> null
    }
}
