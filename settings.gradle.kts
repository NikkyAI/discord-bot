pluginManagement {
    repositories {
//		mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        maven("https://snapshots-repo.kordex.dev")
        maven("https://releases-repo.kordex.dev")
    }
}

plugins {
    id("com.gradle.develocity") version "4.3.2"
    id("de.fayard.refreshVersions") version "0.60.6"
}

refreshVersions {
    extraArtifactVersionKeyRules(file("version_key_rules.txt"))
}

develocity {
    buildScan {
        tag("discordbot")
        termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
        termsOfUseAgree = "yes"
        buildScanPublished {
            file("buildscan.log").appendText("${java.util.Date()} - $buildScanUri\n")
        }
    }
}

rootProject.name = "discordbot"