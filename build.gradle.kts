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
        // subprojects only. The root project legitimately has no sources -- it
        // carries configuration, not code -- so it is excluded by intent rather
        // than by oversight. Stating that here because an unexplained filter is
        // indistinguishable from a missed case.
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

/**
 * Asserts core has no runtime dependencies.
 *
 * The README says core is "No Spring, no I/O -- pure and directly testable",
 * and until now nothing checked it. An unenforced claim reads as coverage
 * without being it, which is the failure this project is about.
 *
 * It is also load-bearing for the dependency-vulnerability gate: that gate's
 * denominator is the component count, and "core has none" is the reason the
 * api module has to supply a real graph rather than the scanner quietly
 * scanning nothing.
 */
val corePurityCheck by tasks.registering {
    group = "verification"
    description = "Fails if :core acquires a runtime dependency."
    doLast {
        val core = project(":core")
        val deps = core.configurations.getByName("runtimeClasspath").resolvedConfiguration
            .resolvedArtifacts.map { it.moduleVersion.id.toString() }.sorted()
        if (deps.isNotEmpty()) {
            throw GradleException(
                ":core has runtime dependencies, and the README says it has none: " +
                    deps.joinToString(", ")
            )
        }
    }
}

tasks.named("build") { dependsOn(emptyModuleCheck, corePurityCheck) }
