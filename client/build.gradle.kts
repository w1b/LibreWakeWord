plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
    `maven-publish`
}

android {
    namespace = "org.librewakeword.client"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.activity.ktx)
    testImplementation(libs.junit4)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "org.librewakeword"
                artifactId = "client"
                version = "0.1.0"
                pom {
                    name.set("LibreWakeWord Android client")
                    description.set("Versioned consumer API for LibreWakeWord")
                    licenses {
                        license {
                            name.set("GNU Affero General Public License v3.0 or later")
                            url.set("https://www.gnu.org/licenses/agpl-3.0.html")
                            distribution.set("repo")
                        }
                    }
                }
            }
        }
    }
}
