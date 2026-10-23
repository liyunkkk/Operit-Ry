pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        ivy {
            name = "SherpaOnnxReleases"
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("org.k2fsa") }
        }
        maven { url = uri("https://dl.bintray.com/rikkaw/Shizuku") }
        maven { url = uri("https://api.xposed.info/") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
    }
}

rootProject.name = "Operit"
include(":app")
include(":accessibility-provider")
include(":chat-parser")
include(":terminal")
include(":showerclient")
include(":quickjs")

include(":chat-storage")
include(":memory-storage")
