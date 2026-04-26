plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

fun Project.findAndroidSdkDir(): File? {
    val fromEnv = providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: providers.environmentVariable("ANDROID_HOME").orNull
    if (!fromEnv.isNullOrBlank()) return file(fromEnv)

    val localProperties = rootProject.file("local.properties")
    if (!localProperties.exists()) return null

    val properties = Properties().apply {
        localProperties.inputStream().use(::load)
    }
    val sdkDir = properties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() } ?: return null
    return file(sdkDir)
}

val androidSdkDir = project.findAndroidSdkDir()
val cmakeNinja = androidSdkDir?.resolve("cmake/3.22.1/bin/ninja")
val vulkanGlslc = androidSdkDir?.resolve("ndk/29.0.13113456/shader-tools/linux-x86_64/glslc")

android {
    namespace = "com.arm.aichat"
    compileSdk = 35

    ndkVersion = "29.0.13113456"

    defaultConfig {
        minSdk = 30

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
             abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DCMAKE_MESSAGE_LOG_LEVEL=DEBUG"
                arguments += "-DCMAKE_VERBOSE_MAKEFILE=ON"
                if (cmakeNinja?.exists() == true) {
                    arguments += "-DCMAKE_MAKE_PROGRAM=${cmakeNinja.absolutePath}"
                }
                targets += "ai-chat"

                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"

                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=OFF"
                arguments += "-DGGML_CPU_ALL_VARIANTS=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                arguments += "-DGGML_VULKAN=ON"
                if (vulkanGlslc?.exists() == true) {
                    arguments += "-DVulkan_GLSLC_EXECUTABLE=${vulkanGlslc.absolutePath}"
                }
            }
        }
        aarMetadata {
            minCompileSdk = 35
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)

        compileOptions {
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    publishing {
        singleVariant("release") {
            withJavadocJar()
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
