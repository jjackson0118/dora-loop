plugins {
    java
    id("org.springframework.boot") version "3.3.4" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "io.github.jjackson0118"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-Xlint:all")
    }
}

/**
 * Negative control for the build itself.
 *
 * An included subproject with no main sources compiles nothing, reports
 * NO-SOURCE, and still rolls up into BUILD SUCCESSFUL. That is the same defect
 * class as a lint gate whose formatter was removed upstream: the check emits
 * nothing and the absence reads as a pass.
 *
 * This task asserts every included subproject actually has something to build.
 */
val emptyModuleCheck by tasks.registering {
    group = "verification"
    description = "Fails if an included subproject has no main sources."
    doLast {
        val empty = subprojects.filter { sp ->
            val srcDir = sp.file("src/main/java")
            !srcDir.exists() || srcDir.walkTopDown().none { it.isFile && it.extension == "java" }
        }
        if (empty.isNotEmpty()) {
            throw GradleException(
                "included subproject(s) with no main sources: " +
                    empty.joinToString(", ") { it.path } +
                    " -- these compile nothing and report BUILD SUCCESSFUL"
            )
        }
    }
}

tasks.named("build") { dependsOn(emptyModuleCheck) }
