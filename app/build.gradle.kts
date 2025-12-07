plugins {
    id("com.google.gms.google-services")
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.sharefilebc"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.sharefilebc"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }

    // ★ここで重複するMETA-INF系ファイルを全部除外する
    packaging {
        resources {
            // さっきの DEPENDENCIES 問題
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"

            // 今回の bcprov / jspecify の衝突
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    applicationVariants.all {
        kotlin.sourceSets.getByName(name) {
            kotlin.srcDir("build/generated/ksp/$name/kotlin/")
        }
    }
}

dependencies {
    implementation("com.jakewharton.threetenabp:threetenabp:1.4.5")
    implementation("com.chaintope.tapyrus.wallet:tapyrus-wallet-android:0.1.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    //Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Firebase - BOMを統一
    implementation(platform("com.google.firebase:firebase-bom:33.12.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth-ktx")

    // Compose関連
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // HomeScreen.kt などで使っているCompose系
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Google サインイン
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    // Google API Client
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.http-client:google-http-client-gson:1.43.3")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1")

    // Google Drive API
    implementation("com.google.apis:google-api-services-drive:v3-rev20230822-2.0.0")

    // Gmail API
    implementation("com.google.apis:google-api-services-gmail:v1-rev20220404-2.0.0")

    // JavaMail API - Gmail送信用
    implementation("com.sun.mail:javax.mail:1.6.2")

    // HTTP クライアント
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // AndroidX
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")

    // ファイル操作
    implementation("commons-io:commons-io:2.13.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // テスト
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
