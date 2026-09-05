plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

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
