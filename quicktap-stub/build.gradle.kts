plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.comrade.quicktaprelay"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.comrade.quicktaprelay"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
