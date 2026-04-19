plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

import java.util.Properties

val signingProperties = Properties().apply {
    val signingFile = rootProject.file("signing.properties")
    if (signingFile.exists()) {
        signingFile.inputStream().use(::load)
    }
}

val localEnvProperties = Properties().apply {
    val envFile = rootProject.file("../.env.local")
    if (envFile.exists()) {
        envFile.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val separatorIndex = line.indexOf('=')
            if (separatorIndex <= 0) return@forEachLine
            val key = line.substring(0, separatorIndex).trim()
            val value = line.substring(separatorIndex + 1).trim()
            setProperty(key, value)
        }
    }
}

val appVersionCode = providers.gradleProperty("appVersionCode")
    .orElse(providers.environmentVariable("APP_VERSION_CODE"))
    .map(String::toInt)
    .getOrElse(1)

val appVersionName = providers.gradleProperty("appVersionName")
    .orElse(providers.environmentVariable("APP_VERSION_NAME"))
    .getOrElse("0.1.0")

val modelImprovementApiBaseUrl = providers.gradleProperty("modelImprovementApiBaseUrl")
    .orElse(providers.environmentVariable("MODEL_IMPROVEMENT_API_BASE_URL"))
    .orElse(localEnvProperties.getProperty("MODEL_IMPROVEMENT_API_BASE_URL") ?: "")
    .getOrElse("")

val modelImprovementCloudProjectNumber = providers.gradleProperty("modelImprovementCloudProjectNumber")
    .orElse(providers.environmentVariable("MODEL_IMPROVEMENT_CLOUD_PROJECT_NUMBER"))
    .orElse(localEnvProperties.getProperty("MODEL_IMPROVEMENT_CLOUD_PROJECT_NUMBER") ?: "")
    .map(String::trim)
    .getOrElse("0")

val modelImprovementCloudProjectNumberLiteral = modelImprovementCloudProjectNumber
    .toLongOrNull()
    ?.let { "${it}L" }
    ?: "0L"

val modelImprovementDebugToken = providers.gradleProperty("modelImprovementDebugToken")
    .orElse(providers.environmentVariable("MODEL_IMPROVEMENT_DEBUG_TOKEN"))
    .orElse(localEnvProperties.getProperty("MODEL_IMPROVEMENT_DEBUG_TOKEN") ?: "")
    .getOrElse("")

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.dreef3.weightlossapp"
    compileSdk = 36
    compileSdkExtension = 19

    defaultConfig {
        applicationId = "com.dreef3.weightlossapp"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "MODEL_IMPROVEMENT_API_BASE_URL", modelImprovementApiBaseUrl.asBuildConfigString())
        buildConfigField("long", "MODEL_IMPROVEMENT_CLOUD_PROJECT_NUMBER", modelImprovementCloudProjectNumberLiteral)
        buildConfigField("String", "MODEL_IMPROVEMENT_DEBUG_TOKEN", "\"\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (signingProperties.getProperty("debugStoreFile") != null) {
            create("customDebug") {
                storeFile = rootProject.file(signingProperties.getProperty("debugStoreFile"))
                storePassword = signingProperties.getProperty("debugStorePassword")
                keyAlias = signingProperties.getProperty("debugKeyAlias")
                keyPassword = signingProperties.getProperty("debugKeyPassword")
            }
        }
        if (signingProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "ENABLE_VERBOSE_LOGGING", "true")
            buildConfigField("String", "MODEL_IMPROVEMENT_DEBUG_TOKEN", modelImprovementDebugToken.asBuildConfigString())
            manifestPlaceholders["appUsesCleartextTraffic"] = "false"
            if (signingConfigs.findByName("customDebug") != null) {
                signingConfig = signingConfigs.getByName("customDebug")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            buildConfigField("boolean", "ENABLE_VERBOSE_LOGGING", "false")
            manifestPlaceholders["appUsesCleartextTraffic"] = "false"
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
        disable += "NullSafeMutableLiveData"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    val roomVersion = "2.8.4"
    val healthConnectVersion = "1.1.0"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.health.connect:connect-client:$healthConnectVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    implementation("com.google.android.gms:play-services-auth:21.4.0")
    implementation("com.google.android.play:integrity:1.6.0")

    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.ai.edge.litert:litert:2.1.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")
    implementation("com.google.android.gms:play-services-tflite-java:16.4.0") {
        exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
    }
    implementation("com.google.android.gms:play-services-tflite-support:16.4.0") {
        exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
    }

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
