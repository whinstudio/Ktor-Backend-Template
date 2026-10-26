plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dependencyCheck)
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("io.cmpose.backendtemplate.MainKt") }

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.jwks.rsa)
    implementation(libs.java.jwt)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.logback)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.embedded.postgres)
    testImplementation(platform(libs.embedded.postgres.bom))
}

tasks.test { useJUnitPlatform() }

// `./gradlew dependencyCheckAnalyze` (CI, needs an NVD API key): fails on a known vulnerability with CVSS >= 9.
dependencyCheck {
    failBuildOnCVSS = 9f
    scanConfigurations = listOf("runtimeClasspath")
    nvd { apiKey = System.getenv("NVD_API_KEY").orEmpty() }
}

tasks.shadowJar {
    archiveFileName.set("app.jar")
    // Shadow 9 drops duplicates before transformers run; keep them so service files really merge.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles() // Flyway's database plugins are found through META-INF/services
}

// Local development server: an embedded PostgreSQL with a persistent data folder and the same module().
// Kept out of `main` so the release jar (shadowJar) carries neither the runner nor the PostgreSQL binaries.
val local: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[local.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[local.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    "localImplementation"(libs.embedded.postgres)
    "localImplementation"(platform(libs.embedded.postgres.bom))
}

sourceSets.test {
    compileClasspath += local.output
    runtimeClasspath += local.output
}

// The local server's folder, relative to this project: `localServerDir=<path>` in gradle.properties or -PlocalServerDir=.
val localServerDir = layout.projectDirectory.dir(providers.gradleProperty("localServerDir").getOrElse(".local-server"))

tasks.register<JavaExec>("runLocal") {
    group = "application"
    description = "Runs the server on 127.0.0.1 with an embedded PostgreSQL kept in .local-server/ (or localServerDir)"
    classpath = local.runtimeClasspath
    mainClass.set("io.cmpose.backendtemplate.local.LocalMainKt")
    args(localServerDir.asFile.absolutePath, layout.projectDirectory.asFile.absolutePath)
}

// Deletes the folder in Kotlin (LocalReset.kt), which first refuses this project, a parent of it or a folder holding
// files the local server did not write.
tasks.register<JavaExec>("resetLocal") {
    group = "application"
    description = "Deletes .local-server/ or localServerDir (database, files and config)"
    classpath = local.runtimeClasspath
    mainClass.set("io.cmpose.backendtemplate.local.LocalResetKt")
    args(localServerDir.asFile.absolutePath, layout.projectDirectory.asFile.absolutePath)
}

// The API description the server serves at /api/v1/openapi.json, as a file for contract checks.
val openApiFile = layout.buildDirectory.file("openapi/openapi.json")

tasks.register<JavaExec>("writeOpenApi") {
    group = "documentation"
    description = "Writes the OpenAPI document to build/openapi/openapi.json"
    classpath = local.runtimeClasspath
    mainClass.set("io.cmpose.backendtemplate.local.OpenApiDumpKt")
    args(openApiFile.get().asFile.absolutePath)
    outputs.file(openApiFile)
}

detekt {
    config.setFrom(files("$rootDir/detekt.yml"))
    buildUponDefaultConfig.set(true)
    parallel.set(true)
    source.setFrom(files("src/main/kotlin", "src/local/kotlin"))
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    jvmTarget.set("21")
    reports {
        html.required.set(true)
        checkstyle.required.set(false)
        sarif.required.set(false)
        markdown.required.set(false)
    }
}
