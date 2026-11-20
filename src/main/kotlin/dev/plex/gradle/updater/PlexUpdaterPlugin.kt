package dev.plex.gradle.updater

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.security.MessageDigest
import java.time.Instant

class PlexUpdaterPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("plexUpdater", PlexUpdaterExtension::class.java)
        extension.server.convention("https://telesphorepo.com")
        extension.repository.convention("plex-update-files")

        // A module's name and API compatibility come from its module.yml; Plex sets its own.
        val moduleYml = project.providers.fileContents(project.layout.projectDirectory.file("src/main/resources/module.yml"))
        val moduleFields = moduleYml.asText.map(::topLevelFields)
        extension.projectName.convention(moduleFields.map { it["name"] })
        extension.requiredApiCompatibility.convention(moduleFields.map { it["apiCompatibility"]?.toInt() })

        // The jar Jenkins archives: the Shadow jar when the project has one, else the plain jar.
        extension.artifact.convention(project.provider {
            project.tasks.findByName("shadowJar") ?: project.tasks.findByName("jar")
        }.flatMap { (it as AbstractArchiveTask).archiveFile })
        extension.artifactPath.convention(extension.artifact.map { it.asFile.relativeTo(project.rootDir).invariantSeparatorsPath })

        project.tasks.register(TASK_NAME, UpdaterMetadataTask::class.java) {
            it.group = "plex"
            it.description = "Writes the updater metadata of this Jenkins build to build/updater, for telesphorepo publish."
            it.artifact.set(extension.artifact)
            it.artifactPath.set(extension.artifactPath)
            it.projectName.set(extension.projectName)
            it.projectVersion.set(project.provider { project.version.toString() })
            it.minecraftVersions.set(extension.minecraftVersions)
            it.apiCompatibility.set(extension.apiCompatibility)
            it.requiredApiCompatibility.set(extension.requiredApiCompatibility)
            it.server.set(extension.server)
            it.repository.set(extension.repository)
            it.outputDirectory.set(project.layout.buildDirectory.dir("updater"))
        }
    }

    private fun topLevelFields(yaml: String): Map<String, String> {
        return yaml.lineSequence()
            .mapNotNull { Regex("""^([A-Za-z][A-Za-z0-9_-]*):\s*(.+?)\s*$""").matchEntire(it) }
            .associate { it.groupValues[1] to it.groupValues[2].trim('"', '\'') }
    }

    companion object {
        const val TASK_NAME = "updaterMetadata"
    }
}

abstract class PlexUpdaterExtension {
    /** The project name Plex asks the updater for: `Plex`, or the `name` of module.yml. */
    abstract val projectName: Property<String>

    abstract val artifact: RegularFileProperty

    /** The artifact's path among the archived artifacts of the Jenkins build. */
    abstract val artifactPath: Property<String>

    /** Plex only: the Minecraft versions the build supports. */
    abstract val minecraftVersions: ListProperty<String>

    /** Plex only: the module API compatibility version Plex provides. */
    abstract val apiCompatibility: Property<Int>

    /** Modules only: the module API compatibility version the module needs. */
    abstract val requiredApiCompatibility: Property<Int>

    abstract val server: Property<String>

    abstract val repository: Property<String>
}

@DisableCachingByDefault(because = "Each Jenkins build writes metadata for its own build number and time.")
abstract class UpdaterMetadataTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifact: RegularFileProperty

    @get:Input
    abstract val artifactPath: Property<String>

    @get:Input
    abstract val projectName: Property<String>

    @get:Input
    abstract val projectVersion: Property<String>

    @get:Input
    abstract val minecraftVersions: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val apiCompatibility: Property<Int>

    @get:Input
    @get:Optional
    abstract val requiredApiCompatibility: Property<Int>

    @get:Input
    abstract val server: Property<String>

    @get:Input
    abstract val repository: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun write() {
        val buildUrl = System.getenv("BUILD_URL")
        val buildNumber = System.getenv("BUILD_NUMBER")
        val commit = System.getenv("GIT_COMMIT")
        if (buildUrl.isNullOrBlank() || buildNumber.isNullOrBlank() || commit.isNullOrBlank()) {
            throw GradleException("$name runs in a Jenkins build: BUILD_URL, BUILD_NUMBER and GIT_COMMIT must be set.")
        }

        val plex = apiCompatibility.isPresent
        if (plex == requiredApiCompatibility.isPresent) {
            throw GradleException(
                "Set plexUpdater.apiCompatibility and plexUpdater.minecraftVersions for Plex, " +
                    "or give a module a module.yml with apiCompatibility."
            )
        }
        if (plex && minecraftVersions.get().isEmpty()) {
            throw GradleException("Set plexUpdater.minecraftVersions to the Minecraft versions this Plex build supports.")
        }

        val name = projectName.get()
        val version = projectVersion.get()
        val channel = if (version.contains("SNAPSHOT")) "dev" else "stable"
        val jar = artifact.get().asFile

        val fields = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "name" to name,
            "version" to version,
            "buildNumber" to buildNumber,
            "commit" to commit,
            "channel" to channel,
            "downloadUrl" to buildUrl.trimEnd('/') + "/artifact/" + artifactPath.get(),
            "sha256" to sha256(jar),
            "size" to jar.length(),
            "publishedAt" to Instant.now().toString(),
        )
        val project = "api/v1/projects/$name"
        val paths = mutableListOf("$project/versions/$version/builds/$buildNumber.json")
        if (plex) {
            fields["minecraftVersions"] = minecraftVersions.get()
            fields["apiCompatibility"] = apiCompatibility.get()
            paths += "$project/channels/$channel/latest.json"
            minecraftVersions.get().forEach { paths += "$project/channels/$channel/latest/minecraft/$it.json" }
        } else {
            fields["requiredApiCompatibility"] = requiredApiCompatibility.get()
            paths += "$project/channels/$channel/latest/api/${requiredApiCompatibility.get()}.json"
        }

        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        out.resolve("telesphorepo.toml").writeText("server = \"${server.get()}\"\nrepo = \"${repository.get()}\"\n")
        val json = json(fields)
        paths.forEach { path ->
            val file = out.resolve(path)
            file.parentFile.mkdirs()
            file.writeText(json)
            logger.lifecycle("Wrote $path")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun json(fields: Map<String, Any>): String = buildString {
        append("{\n")
        fields.entries.forEachIndexed { index, (key, value) ->
            append("  ").append(quote(key)).append(": ")
            when (value) {
                is List<*> -> {
                    append("[\n")
                    value.forEachIndexed { i, element ->
                        append("    ").append(quote(element.toString()))
                        if (i < value.size - 1) append(",")
                        append("\n")
                    }
                    append("  ]")
                }
                is Number -> append(value.toString())
                else -> append(quote(value.toString()))
            }
            if (index < fields.size - 1) append(",")
            append("\n")
        }
        append("}\n")
    }

    private fun quote(text: String): String = buildString {
        append('"')
        text.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
