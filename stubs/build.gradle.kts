plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val androidJar = file("${System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/android-build/sdk"}/platforms/android-37.0/android.jar")

dependencies {
    compileOnly(files(androidJar))
}
