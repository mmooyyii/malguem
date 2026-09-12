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
        // 界面只有中文, 裁掉 appcompat 等库自带的几十种语言资源
        resourceConfigurations += listOf("zh")
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
            // R8 混淆 + 资源裁剪; Gson 反射模型与 jcifs 的 keep 规则见 proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
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
                "META-INF/*.RSA",
                // bouncycastle 的 .properties 白占 1.2MB: jcifs 声明了它, 但实际代码路径用不到,
                // R8 已把它的类删得一个不剩 (dex 里 org/bouncycastle 引用数为 0).
                // 类没了资源还在 —— R8 只管代码, 管不着这些 jar 内资源.
                // 大头是 pqc/crypto/picnic 的三张查找表 (1.21MB, 后量子签名算法, 与 SMB 加密无关),
                // 且是随机数据几乎压不动. 删它们等于砍掉 37% 的包体.
                "org/bouncycastle/**",
                // Kotlin 反射元数据与协程调试文件: okhttp 是 Kotlin 写的但运行时不需要它们 (没用 kotlin-reflect)
                "kotlin/**.kotlin_builtins",
                "DebugProbesKt.bin"
            )
        }
    }
}

dependencies {
    // leanback 与 glide 从未被代码引用 (RecyclerView 原先是 leanback 的传递依赖), 已移除瘦身
    implementation(libs.androidx.recyclerview)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.androidx.appcompat)
    implementation(libs.jsoup)
    implementation(libs.jcifs.ng)
}