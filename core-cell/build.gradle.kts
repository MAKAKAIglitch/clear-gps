plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    systemProperty(
        "java.library.path",
        rootProject.file("rust/cell-intelligence/target/release").absolutePath
    )
    systemProperty(
        "cleargps.requireRustNative",
        providers.gradleProperty("requireRustNative").orElse("false").get()
    )
}
