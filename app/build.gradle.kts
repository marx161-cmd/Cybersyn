import java.net.URLEncoder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

// Cybersyn is signed with the Termux-family platform key so it joins
// sharedUserId="com.termux" (UID 10253, SELinux platform_app) and installs
// as a priv-app. Keys come from ~/.gradle/gradle.properties (the canonical
// homelab TERMUX_* convention), auto-loaded into Gradle project properties.
val releaseKeystorePath = findProperty("TERMUX_KEYSTORE") as String?
val releaseKeystorePassword = findProperty("TERMUX_STORE_PASSWORD") as String?
val releaseKeyAlias = findProperty("TERMUX_KEY_ALIAS") as String?
val releaseKeyPassword = findProperty("TERMUX_KEY_PASSWORD") as String?
val appVersionCode = 78
val appVersionName = "0.2.76"
val allowedDistributions = setOf("standard", "fdroid", "play")
val selectedDistribution = providers.gradleProperty("openTaskerDistribution")
    .orElse("standard")
    .get()
    .lowercase()
require(selectedDistribution in allowedDistributions) {
    "Unsupported OpenTasker distribution '$selectedDistribution'. Expected one of: ${allowedDistributions.joinToString()}."
}
val smsActionAvailable = selectedDistribution != "play"
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.termux.cybersyn.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.termux.cybersyn"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DISTRIBUTION", "\"$selectedDistribution\"")
        buildConfigField("Boolean", "SMS_ACTION_AVAILABLE", smsActionAvailable.toString())
        manifestPlaceholders["smsPermissionName"] = if (smsActionAvailable) "android.permission.SEND_SMS" else "android.permission.INTERNET"
        manifestPlaceholders["phoneStatePermissionName"] = if (smsActionAvailable) "android.permission.READ_PHONE_STATE" else "android.permission.ACCESS_NETWORK_STATE"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    lint {
        abortOnError = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Extract libcybersyn-mqtt.so to nativeLibraryDir as a real executable file so the
        // MqttBridge can exec it (bundled MQTT transport helper).
        jniLibs.useLegacyPackaging = true
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.re2j)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("androidx.work:work-testing:2.11.2")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.register("verifyRoomSchema") {
    group = "verification"
    description = "Checks that all Room schema versions up to the current are exported and tracked."

    dependsOn("kspDebugKotlin")
    val schemaDir = file("$projectDir/schemas/com.termux.cybersyn.core.storage.AppDatabase")
    inputs.dir(schemaDir)

    doLast {
        check(schemaDir.isDirectory) { "Room schema directory missing: $schemaDir" }
        val currentVersion = 8
        val missing = (1..currentVersion).filter { !File(schemaDir, "$it.json").isFile }
        check(missing.isEmpty()) {
            "Room schema files missing for version(s): ${missing.joinToString()}. Run a build to regenerate, then commit."
        }
        println("Room schema drift gate passed: versions 1..$currentVersion present.")
    }
}

