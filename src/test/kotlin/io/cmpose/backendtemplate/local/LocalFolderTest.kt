package io.cmpose.backendtemplate.local

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalFolderTest {
    private val root = createTempDirectory("local-folder").toFile().canonicalFile
    private val project = root.resolve("server").apply { mkdirs() }

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private fun localServerFolder(dir: File) = dir.apply {
        resolve("postgres").mkdirs()
        resolve("files").mkdirs()
        resolve("config.properties").writeText("firebaseProjectId=p\n")
        resolve("server.pid").writeText("1")
        resolve(".DS_Store").writeText("")
    }

    @Test fun theProjectAndItsParentsAreRefused() {
        assertNotNull(localFolderProblem(project, project))
        assertNotNull(localFolderProblem(project.parentFile, project))
    }

    @Test fun aSourceFolderInsideTheProjectIsRefused() {
        val src = project.resolve("src").apply { mkdirs() }
        src.resolve("Main.kt").writeText("")
        val problem = assertNotNull(localFolderProblem(src, project))
        assertContains(problem, src.path)
        assertContains(problem, "Main.kt")
    }

    @Test fun aForeignFolderOutsideTheProjectIsRefused() {
        val shared = root.resolve("shared").apply { mkdirs() }
        shared.resolve("build.gradle.kts").writeText("")
        assertNotNull(localFolderProblem(shared, project))
    }

    @Test fun aMissingOrEmptyFolderIsAccepted() {
        val dir = project.resolve(".local-server")
        assertNull(localFolderProblem(dir, project))
        dir.mkdirs()
        assertNull(localFolderProblem(dir, project))
    }

    @Test fun aFolderWithOnlyLocalServerEntriesIsAccepted() {
        assertNull(localFolderProblem(localServerFolder(project.resolve(".local-server")), project))
        val configOnly = project.resolve("config-only").apply { mkdirs() }
        configOnly.resolve("config.properties").writeText("firebaseProjectId=p\n")
        assertNull(localFolderProblem(configOnly, project))
        assertNull(localFolderProblem(localServerFolder(root.resolve(".local-server")), project))
    }

    @Test fun resetDeletesALocalServerFolder() {
        val dir = localServerFolder(project.resolve(".local-server"))
        assertNull(resetLocalFolder(dir, project))
        assertFalse(dir.exists())
    }

    @Test fun resetRefusesAndKeepsAForeignFolder() {
        val src = project.resolve("src").apply { mkdirs() }
        val main = src.resolve("Main.kt").apply { writeText("") }
        assertNotNull(resetLocalFolder(src, project))
        assertTrue(main.exists())
    }
}
