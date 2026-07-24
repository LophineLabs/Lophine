import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java // TODO java launcher tasks
    id("moe.luminolmc.hyacinthusweight.patcher")
}

paperweight {
    filterPatches = false
    upstreams.register("folia") {
        repo = github("PaperMC", "Folia")
        ref = providers.gradleProperty("foliaRef")

        println("Upstream commit ref: " + ref.get())

        patchFile {
            path = "folia-server/build.gradle.kts"
            outputFile = file("lophine-server/build.gradle.kts")
            patchFile = file("lophine-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "folia-api/build.gradle.kts"
            outputFile = file("lophine-api/build.gradle.kts")
            patchFile = file("lophine-api/build.gradle.kts.patch")
        }
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("lophine-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("foliaApi") {
            upstreamPath = "folia-api"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("lophine-api/folia-patches")
            outputDir = file("folia-api")
        }
    }
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"
val bacteriawaMavenPublicUrl = "https://repo.bacteriawa.com/repository/maven-public/";

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven(bacteriawaMavenPublicUrl)
    }

    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://repo.bacteriawa.com/repository/maven-releases/") {
                name = "Bacteriawa"
                credentials(PasswordCredentials::class) {
                    username = System.getenv("PRIVATE_MAVEN_REPO_USERNAME")
                    password = System.getenv("PRIVATE_MAVEN_REPO_PASSWORD")
                }
            }
        }
    }

    tasks.withType<Javadoc>().configureEach {
        options {
            (this as StandardJavadocDocletOptions).apply {
                addStringOption("-add-modules", "jdk.incubator.vector")
                addStringOption("Xdoclint:none", "-quiet")
            }
        }
    }
}

// Sort all JSON language files under the lang directory by key in ASCII order
val langDir = layout.projectDirectory.dir("lophine-server/src/main/resources/assets/lophine/lang")
tasks.register("sortLangKeys") {
    group = "lophine"
    description = "Sort all JSON language files by key in ASCII (ordinal) order"
    notCompatibleWithConfigurationCache("Inline task action references build script class")
    inputs.dir(langDir).optional()
    outputs.dir(langDir)
    doLast {
        val dir = langDir.asFile
        if (!dir.isDirectory) {
            logger.warn("Lang directory not found: $dir")
            return@doLast
        }
        val jsonFiles = dir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name } ?: emptyList()
        if (jsonFiles.isEmpty()) {
            logger.lifecycle("No .json files found in: $dir")
            return@doLast
        }
        val slurper = JsonSlurper()
        for (file in jsonFiles) {
            @Suppress("UNCHECKED_CAST")
            val data = slurper.parse(file) as Map<String, Any?>
            val sorted = data.toSortedMap()
            val json = JsonOutput.toJson(sorted)
            // Pretty print with 2-space indent
            val pretty = JsonOutput.prettyPrint(json)
            file.writeText(pretty + "\n", charset = Charsets.UTF_8)
            logger.lifecycle("Processed: ${file.name}  (${sorted.size} keys)")
        }
        logger.lifecycle("Done.")
    }
}
