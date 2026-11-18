plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// whisper.cpp 네이티브 라이브러리 빌드 여부 (CI/개발 PC: true, 네이티브 없이 Kotlin만 확인할 때: false)
val buildNative = (findProperty("kosub.native") as String?)?.toBoolean() ?: true

android {
    namespace = "com.kosub.player"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.kosub.player"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            // 갤럭시 S25 등 최신 폰은 모두 64비트 ARM
            abiFilters += listOf("arm64-v8a")
        }
        buildConfigField("boolean", "HAS_NATIVE", buildNative.toString())
        if (buildNative) {
            externalNativeBuild {
                cmake {
                    // 갤럭시 S25(Snapdragon 8 Elite) 등 2019년 이후 폰의 ARM dotprod/fp16 명령어로 음성 인식을 빠르게
                    arguments += listOf(
                        "-DANDROID_STL=c++_static",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    )
                    cFlags += "-march=armv8.2-a+dotprod+fp16"
                    cppFlags += "-march=armv8.2-a+dotprod+fp16"
                }
            }
        }
    }

    signingConfigs {
        // 저장소에 포함된 디버그 키: 새 버전 APK를 덮어 설치할 수 있도록 항상 같은 키로 서명한다
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // 앱 크기를 줄인다 (Claude SDK는 자체 R8 규칙을 함께 배포한다)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    if (buildNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
            )
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Claude 번역 (공식 Java SDK)
    implementation("com.anthropic:anthropic-java:3.10.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
