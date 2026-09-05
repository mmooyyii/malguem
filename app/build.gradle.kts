import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String): String? =
    localProps.getProperty(key) ?: System.getenv(key)

android {
    namespace = "com.github.mmooyyii.malguem"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.github.mmooyyii.malguem"
        minSdk = 26
        targetSdk = 35
        // CI 打 tag 时注入: versionName=tag名(应用内更新用它和 GitHub Release 的 tag_name 比较),
        // versionCode=run_number(单调递增, 否则系统拒绝覆盖安装)
        versionCode = (prop("VERSION_CODE") ?: "1").toInt()
        versionName = prop("VERSION_NAME") ?: "dev"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = prop("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = prop("RELEASE_STORE_PASSWORD")
                keyAlias = prop("RELEASE_KEY_ALIAS")
                keyPassword = prop("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (prop("RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            // jcifs-ng 依赖 bouncycastle(签名 jar) 与 slf4j, 去掉重复/签名文件避免打包冲突
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.leanback)
    implementation(libs.glide)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.androidx.appcompat)
    implementation(libs.jsoup)
    implementation(libs.jcifs.ng)
}