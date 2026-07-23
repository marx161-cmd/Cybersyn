plugins {
    alias(libs.plugins.android.application)
}

val releaseKeystorePath = findProperty("TERMUX_KEYSTORE") as String?
val releaseKeystorePassword = findProperty("TERMUX_STORE_PASSWORD") as String?
val releaseKeyAlias = findProperty("TERMUX_KEY_ALIAS") as String?
val releaseKeyPassword = findProperty("TERMUX_KEY_PASSWORD") as String?
val hasReleaseSigning = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.termux.cybersyn.stub2"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.termux.cybersyn.stub2"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
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
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
