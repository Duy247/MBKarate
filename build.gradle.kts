plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "com.mb"
version = "1.6.0"
val platformVersion = providers.gradleProperty("platformVersion").getOrElse("2025.1.4.1")

repositories {
    mavenLocal()    
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create("IC", platformVersion)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.modules.json")
        bundledPlugin("org.intellij.intelliLang")
        bundledPlugin("JUnit")
        
        // Resolve a Gherkin plugin version compatible with the selected IntelliJ Platform.
        compatiblePlugin("gherkin")
        
        // The required plugins are defined in plugin.xml
    }
    // implementation("com.duy.karate.test:karate-feature-demo:1.0-SNAPSHOT")
    implementation("org.json:json:20240303")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            <h3>Version 1.6.0</h3>
            <ul>
              <li>Minor change: fix API usage to be compatible with 2026.1</li>
              <li>Minor change: Autocomplete now accept DEFAULT value with tag DEFAULT</li>
              <li>Minor change: Add new feature: scenario impact change</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    
    // Skip the buildSearchableOptions task that's causing issues
    buildSearchableOptions {
        enabled = false
    }
    prepareJarSearchableOptions {
        enabled = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
