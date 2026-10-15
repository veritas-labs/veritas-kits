plugins {
    `maven-publish`
}

group = "uk.veritaslabs.kits"
version = providers.gradleProperty("kitsVersion").get()

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set(project.name)
            description.set("Vesta Kits: ${project.name}")
            url.set("https://veritaslabs.uk")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    name.set("Veritas Labs")
                }
            }
        }
    }
}
