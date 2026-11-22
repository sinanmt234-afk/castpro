plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.castpro.phone"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.castpro.phone"
        minSdk = 24; targetSdk = 34; versionCode = 1; versionName = "1.0"
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
}
