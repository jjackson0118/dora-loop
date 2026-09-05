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
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    manifest {
        attributes("Implementation-Version" to (project.findProperty("buildSha") ?: "unknown"))
    }
}
