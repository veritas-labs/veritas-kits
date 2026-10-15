// Vesta-like consumer: mavenLocal is filtered to the kits group only.
rootProject.name = "kits-consume-check"
pluginManagement {
    repositories {
        google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") } }
        mavenCentral(); gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroup("uk.veritaslabs.kits") } }
        google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") } }
        mavenCentral()
    }
}
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
include(":shared", ":desktop")
