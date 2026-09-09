import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Community, not Ultimate: `intellijIdea(...)` resolves to IU, which runs
        // unlicensed in the sandbox and disables its paid plugins.
        intellijIdeaCommunity("2025.2.6.2")

        // Free Python support (not bundled in IC). Version pinned to the one the
        // Marketplace reports compatible with IC-252.28539.54.
        plugin("PythonCore", "252.28539.97")
        testFramework(TestFrameworkType.Platform)
    }
}
