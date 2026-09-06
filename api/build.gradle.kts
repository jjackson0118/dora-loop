plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// Overrides the version Boot 3.3.4 manages (1.19.8), which cannot talk to a
// modern engine: its bundled docker-java negotiates Docker API 1.32, and
// Docker 29 refuses anything below 1.44 with "client version is too old".
// Pinned explicitly rather than left to the BOM because the failure is
// environment-dependent -- a CI runner on an older engine would go green while
// a developer on a current one could not run the suite at all, which is the
// local-vs-CI divergence this project has already been bitten by once.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation(project(":core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // JDBC, not JPA. Three tables do not need an ORM, and Hibernate would add
    // ~15 jars and lazy-loading semantics for no benefit here.
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // A real Postgres, because the parts of this module that can be wrong are
    // the parts an ObjectMapper never touches: the migration, the SQL, the
    // transaction boundaries, and the status codes. An in-memory H2 would run
    // faster and would not be running this schema.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

// The deployed artifact must be able to say which commit it is, and the smoke
// test asserts against it. Read from the build, never from a file the deploy
// writes -- otherwise the assertion is against the deploy's own claim about
// itself.
//
// That comment was already here and nothing acted on it. The manifest
// attribute below was written and never read, while /actuator/info reported
// ${DORA_BUILD_SHA}, an environment variable the deploy sets. So the check the
// deploy script called its read-back was asking the deploy to confirm its own
// claim, and a reviewer proved it: a release directory named "revtest2"
// containing a jar byte-identical to another build reported "serving revtest2"
// and the deploy passed.
//
// buildInfo() generates META-INF/build-info.properties into the artifact, which
// Boot exposes at /actuator/info with no code of ours. It is on the classpath
// in tests too, so the property can be asserted rather than only observed in
// production.
val buildSha: String =
    (project.findProperty("buildSha") as String?)?.takeIf { it.isNotBlank() }
        ?: runCatching {
            providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
                .standardOutput.asText.get().trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "unknown"

springBoot {
    buildInfo {
        // excludes, not properties { time = null }. Setting the property to
        // null compiled and did nothing -- the plugin read it as "use the
        // default" and stamped the build time anyway, which two clean builds
        // then proved by producing different jars. Verified by diffing the
        // extracted archives: build-info.properties was the only file that
        // differed, and build.time was the only line in it.
        excludes.set(setOf("time"))
        properties {
            additional.put("sha", buildSha)
        }
    }
}

// Reproducible archives. Removing build.time was necessary and not sufficient:
// a jar is a zip, and Gradle stamps each entry with the file's mtime and walks
// the tree in filesystem order, so two clean builds of the same commit still
// produced different sha256s -- measured, twice.
//
// This matters because the deploy verifies the artifact by sha256 and the whole
// point of that check is to answer "is the thing running the thing we built".
// If "the artifact for this commit" is not a stable object, the checksum
// verifies only that a file survived a network transfer, which is the weaker
// half of what it appears to promise.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    manifest {
        // Same value, second surface. One source so the two cannot disagree.
        attributes("Implementation-Version" to buildSha)
    }
}
