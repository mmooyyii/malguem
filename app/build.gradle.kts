import com.android.build.api.dsl.Packaging


plugins {
    alias(libs.plugins.android.application)
}


android {
    namespace = "com.github.mmooyyii.malguem"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.mmooyyii.malguem"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(libs.androidx.leanback)
    implementation(libs.glide)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.androidx.appcompat)
    implementation("io.documentnode:epub4j-core:4.2.1") {
        exclude(group = "xmlpull")
    }
}