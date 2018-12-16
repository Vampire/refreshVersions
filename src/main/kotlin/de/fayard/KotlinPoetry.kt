package de.fayard

import com.squareup.kotlinpoet.*
import java.util.*

internal val LibsClassName = "Libs"
internal val VersionsClassName = "Versions"


/**
 * We don't want to use meaningless generic libs like Libs.core
 *
 * Found many inspiration for bad libs here https://developer.android.com/jetpack/androidx/migrate
 * **/
val MEANING_LESS_NAMES: List<String> = listOf(
    "common", "core", "core-testing", "testing", "runtime", "extensions",
    "compiler", "migration", "db", "rules", "runner", "monitor", "loader",
    "media", "print", "io", "media", "collection", "gradle", "android"
)

val INITIAL_GITIGNORE = """
.gradle/
build/
"""

val INITIAL_SETTINGS = ""

val GRADLE_KDOC = """
  To update Gradle, edit the wrapper file at path:
     ./gradle/wrapper/gradle-wrapper.properties
"""

val KDOC_LIBS = """
    Generated by https://github.com/jmfayard/buildSrcVersions

    Update this file with
      `$ ./gradlew buildSrcVersions`
    """.trimIndent()

val KDOC_VERSIONS = """
    Find which updates are available by running
        `$ ./gradlew buildSrcVersions`
    This will only update the comments.

    YOU are responsible for updating manually the dependency version.
    """.trimIndent()


const val INITIAL_BUILD_GRADLE_KTS = """
plugins {
    `kotlin-dsl`
}
repositories {
    jcenter()
}
        """



@Suppress("LocalVariableName")
fun kotlinpoet(versions: List<Dependency>, gradleConfig: GradleConfig): KotlinPoetry {

    val versionsProperties: List<PropertySpec> = versions
        .distinctBy { it.versionName }
        .map { d: Dependency ->
            constStringProperty(
                name = d.versionName,
                initializer = CodeBlock.of("%S %L", d.version, d.versionInformation())
            )
        }
    val libsProperties: List<PropertySpec> = versions
        .distinctBy { it.escapedName }
        .map { d ->
            constStringProperty(
                name = d.escapedName,
                initializer = CodeBlock.of("%S + Versions.%L", "${d.group}:${d.name}:", d.versionName),
                kdoc = dependencyKdoc(d)
            )
        }

    val gradleProperties: List<PropertySpec> = listOf(
        constStringProperty("runningVersion", gradleConfig.running.version),
        constStringProperty("currentVersion", gradleConfig.current.version),
        constStringProperty("nightlyVersion", gradleConfig.nightly.version),
        constStringProperty("releaseCandidate", gradleConfig.releaseCandidate.version)
    )

    val Gradle: TypeSpec = TypeSpec.objectBuilder("Gradle")
        .addProperties(gradleProperties)
        .addKdoc(GRADLE_KDOC)
        .build()

    val Versions: TypeSpec = TypeSpec.objectBuilder("Versions")
        .addKdoc(KDOC_VERSIONS)
        .addType(Gradle).addProperties(versionsProperties)
        .build()


    val Libs = TypeSpec.objectBuilder("Libs")
        .addKdoc(KDOC_LIBS)
        .addProperties(libsProperties)
        .build()


    val LibsFile = FileSpec.builder("", LibsClassName)
        .addType(Libs)
        .build()

    val VersionsFile = FileSpec.builder("", VersionsClassName)
        .addType(Versions)
        .build()

    return KotlinPoetry(Libs = LibsFile, Versions = VersionsFile)

}

private fun dependencyKdoc(d: Dependency): CodeBlock? {
    return if (d.projectUrl == null) null
    else CodeBlock.of("%L", d.projectUrl)
}


fun BuildSrcVersionsTask.Companion.parseGraph(
    graph: DependencyGraph,
    useFdqnByDefault: List<String>
): List<Dependency> {

    val dependencies: List<Dependency> = graph.current + graph.exceeded + graph.outdated + graph.unresolved

    val map = mutableMapOf<String, Dependency>()
    for (d: Dependency in dependencies) {
        val key = escapeName(d.name)
        val fdqnName = d.fdqnName()


        if (key in useFdqnByDefault) {
            d.escapedName = fdqnName
        } else if (map.containsKey(key)) {
            d.escapedName = fdqnName

            // also use FDQN for the dependency that conflicts with this one
            val other = map[key]!!
            other.escapedName = other.fdqnName()
            println("Will use FDQN for ${other.escapedName}")
        } else {
            map[key] = d
            d.escapedName = key
        }
    }
    return dependencies.orderDependencies().findCommonVersions()
}

fun Dependency.fdqnName(): String = escapeName("${group}_${name}")


fun List<Dependency>.orderDependencies(): List<Dependency> {
    return this.sortedBy { it.gradleNotation() }
}

fun List<Dependency>.findCommonVersions(): List<Dependency> {
    val map = groupBy { d -> d.group }
    for (deps in map.values) {
        val groupTogether = deps.size > 1  && deps.map { it.version }.distinct().size == 1

        for (d in deps) {
            d.versionName = if (groupTogether) escapeName(d.group) else d.escapedName
        }
    }
    return this
}

fun constStringProperty(name: String, initializer: CodeBlock, kdoc: CodeBlock? = null) =
    PropertySpec.builder(name, String::class)
        .addModifiers(KModifier.CONST)
        .initializer(initializer)
        .apply {
            if (kdoc != null) addKdoc(kdoc)
        }.build()


fun constStringProperty(name: String, initializer: String, kdoc: CodeBlock? = null) =
    constStringProperty(name, CodeBlock.of("%S", initializer))


fun escapeName(name: String): String {
    val escapedChars = listOf('-', '.', ':')
    return buildString {
        for (c in name) {
            append(if (c in escapedChars) '_' else c.toLowerCase())
        }
    }
}

fun Dependency.versionInformation(): String {
    val comment = available?.displayComment() ?: ""
    return if (comment.length + versionName.length > 65) {
        '\n' + comment
    } else {
        comment
    }
}



fun AvailableDependency.displayComment(): String {
    val newerVersion: String? = when {
        release.isNullOrBlank().not() -> release
        milestone.isNullOrBlank().not() -> milestone
        integration.isNullOrBlank().not() -> integration
        else -> null
    }
    return  if (newerVersion == null) "//$this" else """// available: "$newerVersion""""
}


private val random = Random()


