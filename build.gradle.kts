import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "com.kgtech"
// no `version`: it would be a version number outside the catalog (S10)

java { toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt()) } }

repositories { mavenCentral() }

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES)) // BOM without a second plugin or version
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    developmentOnly(platform(SpringBootPlugin.BOM_COORDINATES)) // bootJar resolves this configuration on its own
    developmentOnly(libs.spring.boot.docker.compose) // bootRun starts compose.yaml; excluded from bootJar (D8)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-classfile", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("inventory.test.postgres-image", "postgres:${libs.versions.postgres.get()}")
}
