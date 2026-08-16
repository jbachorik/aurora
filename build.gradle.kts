plugins {
    application
}

// ---------------------------------------------------------------------------
// Project coordinates
// ---------------------------------------------------------------------------

val applicationModuleName = "media.center"
val applicationMainClass = "mediacenter.Main"
val applicationImageName = "MediaCenter"

/**
 * Name of the redistributable archive. Carries the project name, while the
 * launcher inside the image stays "MediaCenter" — that is what gets
 * double-clicked on the target machine.
 */
val distributionName = "Aurora-MediaCenter"

version = providers.gradleProperty("applicationVersion").getOrElse("1.0.0")
group = "mediacenter"

val javaLanguageVersion = providers.gradleProperty("javaLanguageVersion").getOrElse("25").toInt()
val javafxVersion = providers.gradleProperty("javafxVersion").getOrElse("25.0.4")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaLanguageVersion)
    }
}

repositories {
    mavenCentral()
}

// ---------------------------------------------------------------------------
// JavaFX
//
// The reference JDK (Liberica JDK 25 "Full") ships the JavaFX modules as part
// of the JDK itself, including the jmods that jlink needs. When such a JDK is
// selected the project has *zero* runtime dependencies and nothing has to be
// resolved from a repository.
//
// On a plain JDK (no bundled JavaFX) the OpenJFX artifacts from Maven Central
// are used instead so that `compileJava`, `test` and `run` still work. That
// fallback is a development convenience only: jlink needs real jmods, so the
// production image must be built with a Full JDK (or with -PjavafxJmods=<dir>
// pointing at a directory of JavaFX jmods).
// ---------------------------------------------------------------------------

val javaToolchainService = extensions.getByType<JavaToolchainService>()
val toolchainHome: Directory =
    javaToolchainService.launcherFor(java.toolchain).get().metadata.installationPath

val jdkJmodsDir: Directory = toolchainHome.dir("jmods")
val jdkBundlesJavaFx: Boolean = jdkJmodsDir.file("javafx.controls.jmod").asFile.isFile

val javafxJmodsOverride: String? = providers.gradleProperty("javafxJmods").orNull

val javafxPlatformClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val aarch64 = arch == "aarch64" || arch == "arm64"
    when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (aarch64) "mac-aarch64" else "mac"
        else -> if (aarch64) "linux-aarch64" else "linux"
    }
}

dependencies {
    if (!jdkBundlesJavaFx) {
        // Platform-classified artifacts are self-contained modular jars
        // (classes + native libraries), which keeps the module path simple.
        for (javafxModule in listOf("base", "graphics", "controls")) {
            implementation("org.openjfx:javafx-$javafxModule:$javafxVersion:$javafxPlatformClassifier")
        }
    }

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainModule = applicationModuleName
    mainClass = applicationMainClass
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to applicationImageName,
            "Implementation-Version" to project.version.toString()
        )
    }
}

// Tests exercise the plain (non-JavaFX) logic and run on the classpath; there
// is no test module descriptor and no reason to patch the application module.
tasks.named<JavaCompile>("compileTestJava") {
    modularity.inferModulePath = false
}

tasks.test {
    modularity.inferModulePath = false
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// ---------------------------------------------------------------------------
// jlink — mandatory for a production build
// ---------------------------------------------------------------------------

// jlink refuses to write into a directory that already exists, and Gradle
// creates declared output directories before a task runs — so the declared
// output is the parent and jlink creates "image" inside it.
val runtimeImageParentDir = layout.buildDirectory.dir("runtime")
val runtimeImageDir = layout.buildDirectory.dir("runtime/image")
val appImageDir = layout.buildDirectory.dir("app-image")

val javafxJmodsDir: String? = when {
    javafxJmodsOverride != null -> javafxJmodsOverride
    jdkBundlesJavaFx -> null // already covered by the JDK's own jmods directory
    else -> null
}

val jlinkModulePath: Provider<String> = providers.provider {
    val entries = mutableListOf<String>()
    entries += jdkJmodsDir.asFile.absolutePath
    javafxJmodsDir?.let { entries += file(it).absolutePath }
    entries += tasks.jar.get().archiveFile.get().asFile.absolutePath
    configurations.runtimeClasspath.get().files.forEach { dependency ->
        // When JavaFX jmods are supplied, the OpenJFX jars would offer the same
        // modules a second time and jlink refuses an ambiguous module path.
        val isJavafxJar = dependency.name.startsWith("javafx-")
        if (javafxJmodsDir == null || !isJavafxJar) {
            entries += dependency.absolutePath
        }
    }
    entries.joinToString(File.pathSeparator)
}

val deleteRuntimeImage = tasks.register<Delete>("deleteRuntimeImage") {
    delete(runtimeImageParentDir)
}

val jlink = tasks.register<Exec>("jlink") {
    group = "distribution"
    description = "Builds a self-contained runtime image containing only the required modules."

    dependsOn(tasks.jar, deleteRuntimeImage)
    inputs.files(tasks.jar)
    outputs.dir(runtimeImageParentDir)

    val jlinkExecutable = toolchainHome.file("bin/jlink").asFile.let {
        if (it.isFile) it.absolutePath else toolchainHome.file("bin/jlink.exe").asFile.absolutePath
    }
    val outputPath = runtimeImageDir.get().asFile.absolutePath
    val modulePath = jlinkModulePath
    val hasJavaFxJmods = jdkBundlesJavaFx || javafxJmodsDir != null
    val toolchainDescription = toolchainHome.asFile.absolutePath

    doFirst {
        if (!hasJavaFxJmods) {
            throw GradleException(
                """
                No JavaFX jmods found.

                jlink needs JavaFX *jmods*, which the selected JDK does not provide:
                    $toolchainDescription

                Build the production image with a "Full" JDK distribution (Liberica JDK
                $javaLanguageVersion Full bundles them), or pass a jmods directory explicitly:
                    ./gradlew jlink -PjavafxJmods=/path/to/javafx-jmods
                """.trimIndent()
            )
        }
    }

    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "--module-path", modulePath.get(),
                "--add-modules", applicationModuleName,
                "--launcher", "$applicationImageName=$applicationModuleName/$applicationMainClass",
                "--output", outputPath,
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--compress", "zip-6"
            )
        }
    )
    executable = jlinkExecutable
}

// ---------------------------------------------------------------------------
// jpackage — native launcher (app-image only, no installer)
// ---------------------------------------------------------------------------

val deleteAppImage = tasks.register<Delete>("deleteAppImage") {
    delete(appImageDir)
}

val jpackage = tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Builds a native application image (jpackage --type app-image) around the jlink runtime."

    dependsOn(jlink, deleteAppImage)
    inputs.dir(runtimeImageDir)
    outputs.dir(appImageDir)

    val jpackageExecutable = toolchainHome.file("bin/jpackage").asFile.let {
        if (it.isFile) it.absolutePath else toolchainHome.file("bin/jpackage.exe").asFile.absolutePath
    }
    val runtimePath = runtimeImageDir.get().asFile.absolutePath
    val destPath = appImageDir.get().asFile.absolutePath
    val appVersion = project.version.toString()
    // A macOS .app bundle wants a reverse-DNS identifier; the other platforms
    // have no equivalent and reject the option.
    val platformOptions = if (hostIsMac) {
        listOf("--mac-package-identifier", "com.simplemediacenter.mediacenter")
    } else {
        emptyList()
    }

    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "--type", "app-image",
                "--name", applicationImageName,
                "--app-version", appVersion,
                "--vendor", "SimpleMediaCenter",
                "--description", "Lightweight media center frontend",
                "--runtime-image", runtimePath,
                "--module", "$applicationModuleName/$applicationMainClass",
                "--dest", destPath
            ) + platformOptions
        }
    )
    executable = jpackageExecutable
}

// ---------------------------------------------------------------------------
// Distribution zip — MediaCenter-windows-x64.zip and friends
// ---------------------------------------------------------------------------

val hostOsName: String = System.getProperty("os.name").lowercase()
val hostIsWindows: Boolean = hostOsName.contains("win")
val hostIsMac: Boolean = hostOsName.contains("mac")

val distributionPlatform: String = run {
    val os = hostOsName
    val arch = System.getProperty("os.arch").lowercase()
    val archToken = if (arch == "aarch64" || arch == "arm64") "aarch64" else "x64"
    val osToken = when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "macos"
        else -> "linux"
    }
    "$osToken-$archToken"
}

val distributionsDir = layout.buildDirectory.dir("distributions")
val distributionArchiveName = "$distributionName-$distributionPlatform.zip"

// zip appends to an existing archive, so a stale one has to go first.
val deleteDistributionArchive = tasks.register<Delete>("deleteDistributionArchive") {
    delete(distributionsDir.map { it.file(distributionArchiveName) })
}

/*
 * Two ways to build the same archive, because the platforms differ in what has
 * to survive it:
 *
 *  - On Linux and macOS the image contains executable files, and a macOS .app
 *    bundle also contains symbolic links inside its runtime. Gradle's archive
 *    tasks normalize permissions and follow symlinks, so the system zip is used
 *    instead: -y stores links rather than resolving them, and permissions are
 *    preserved as recorded.
 *  - On Windows neither applies, and zip is not on the runners, so Gradle's own
 *    Zip task does the job.
 */
val packageZip = if (hostIsWindows) {
    tasks.register<Zip>("packageZip") {
        group = "distribution"
        description = "Packages the jpackage application image into a redistributable ZIP."

        dependsOn(jpackage)
        from(appImageDir)
        archiveFileName = distributionArchiveName
        destinationDirectory = distributionsDir
    }
} else {
    tasks.register<Exec>("packageZip") {
        group = "distribution"
        description = "Packages the jpackage application image into a redistributable ZIP."

        dependsOn(jpackage, deleteDistributionArchive)
        inputs.dir(appImageDir)
        // Declaring the directory as an output makes Gradle create it for us.
        outputs.dir(distributionsDir)

        workingDir = appImageDir.get().asFile
        val archivePath = distributionsDir.get().file(distributionArchiveName).asFile.absolutePath
        // -q quiet, -r recurse, -y store symlinks instead of following them.
        commandLine("zip", "-q", "-r", "-y", archivePath, ".")
    }
}
