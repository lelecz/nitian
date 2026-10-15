plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lelecz.reply"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.lelecz.reply"
        minSdk = 26          // 安卓 8.0
        targetSdk = 35
        versionCode = 35
        versionName = "1.9.4"
    }

    // 专属签名配置：debug 和 release 共用同一套签名，保证每次构建签名一致
    // keystore 明文只在本机（gitignore）；仓库里是加密版 release.keystore.enc
    // 丢失时解密：openssl enc -d -aes-256-cbc -pbkdf2 -in release.keystore.enc -out release.keystore
    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = "lelecz"
            keyAlias = "rereply"
            keyPassword = "lelecz"
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        noCompress += "traineddata"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation("com.rmtheis:tess-two:9.1.0")
}
