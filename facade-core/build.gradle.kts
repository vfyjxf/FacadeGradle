plugins {
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("facadeCore") {
            from(components["java"])
            pom {
                name = "${project.group}:${project.name}"
                description =
                    "Core of FacadeGradle: rule model, ASM interface-injecting class transformer and jar transformer."
                url = "https://github.com/vfyjxf/FacadeGradle"
                scm {
                    url = "https://github.com/vfyjxf/FacadeGradle"
                    connection = "scm:git:https://github.com/vfyjxf/FacadeGradle.git"
                    developerConnection = "scm:git:https://github.com/vfyjxf/FacadeGradle.git"
                }
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
                developers {
                    developer {
                        id.set("vfyjxf")
                        name.set("vfyjxf")
                    }
                }
            }
        }
    }
}

dependencies {
    api("org.ow2.asm:asm:9.7.1")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("org.ow2.asm:asm-util:9.7.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
