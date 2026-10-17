package io.cmpose.backendtemplate

import io.cmpose.backendtemplate.files.ContentSniffer
import io.cmpose.backendtemplate.files.LocalDiskFileStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStoreTest {
    private lateinit var base: Path
    private lateinit var root: Path
    private lateinit var store: LocalDiskFileStore

    @BeforeTest
    fun setUp() {
        base = createTempDirectory("store-test")
        root = base.resolve("files").createDirectories()
        store = LocalDiskFileStore(root)
    }

    @Test
    fun writeReadDelete() {
        val path = store.write(PNG_BYTES)
        assertContentEquals(PNG_BYTES, store.read(path))
        store.delete(path)
        assertNull(store.read(path))
        store.delete(path) // a missing file is ignored
    }

    @Test
    fun namesAreRandom() {
        val first = store.write(PNG_BYTES)
        val second = store.write(PNG_BYTES)
        assertNotEquals(first, second)
        assertEquals(listOf(first, second).sorted(), root.listDirectoryEntries().map { it.name }.sorted())
    }

    @Test
    fun pathTraversalIsRejected() {
        val outside = base.resolve("x").also { it.writeText("keep") }
        listOf("../x", "/etc/passwd", "a/b", "", "..", root.resolve("../x").toString()).forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) { store.read(path) }
            assertFailsWith<IllegalArgumentException>(path) { store.delete(path) }
        }
        assertTrue(outside.exists())
    }

    @Test
    fun storedNamesDoNotContainTheUploadName() = testApplication {
        val config = testConfig().copy(filesDir = root.toString())
        TestDb.reset()
        application { module(authDeps(config = config)) }
        val response = jsonClient().upload(TestKeys.token(sub = "alice"), filename = "holiday-photo.png")
        assertEquals(HttpStatusCode.Created, response.status)
        val names = root.listDirectoryEntries().map { it.name }
        assertEquals(1, names.size)
        assertFalse(names.single().contains("holiday"))
        assertFalse(names.single().contains(".png"))
    }

    @Test
    fun snifferKnowsOnlyTheAllowedTypes() {
        fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)
        assertEquals("image/png", ContentSniffer.detect(PNG_BYTES))
        assertEquals("image/jpeg", ContentSniffer.detect(bytes("ÿØÿàxx")))
        assertEquals("image/webp", ContentSniffer.detect(bytes("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
        assertEquals("application/pdf", ContentSniffer.detect(bytes("%PDF-1.7\n")))
        assertNull(ContentSniffer.detect(bytes("MZ\u0090\u0000")))
        assertNull(ContentSniffer.detect(bytes("RIFF\u0000\u0000\u0000\u0000WAVE")))
        assertNull(ContentSniffer.detect(bytes("<svg xmlns='http://www.w3.org/2000/svg'/>")))
        assertNull(ContentSniffer.detect(ByteArray(0)))
    }
}
