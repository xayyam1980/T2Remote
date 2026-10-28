plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.t2remote.home"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.t2remote.home"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        create("t2") {
            storeFile = rootProject.file("t2remote.keystore")
            storePassword = "t2remote"; keyAlias = "t2"; keyPassword = "t2remote"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("t2")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
// No libraries: plain Android views keep memory use minimal on 1 GB devices.
