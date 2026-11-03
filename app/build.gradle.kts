plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ru.mercedesdoctorai"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.mercedesdoctorai"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5-test"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
}
