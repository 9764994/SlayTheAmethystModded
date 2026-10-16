plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        create("androidAppBuildPlugin") {
            id = "io.stamethyst.android-app-build"
            implementationClass = "StsAndroidAppBuildPlugin"
        }
    }
}
