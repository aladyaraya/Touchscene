import org.gradle.kotlin.dsl.implementation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
}

// Gradle 只自动加载 gradle.properties；local.properties 需手动读，
// 否则凭据静默为空、远程 AI 永远走本地兜底
val touchSceneLocalProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.isFile) f.inputStream().use(::load)
    }

fun touchSceneProperty(name: String): String? =
    (project.findProperty(name) as? String)?.takeIf { it.isNotBlank() }
        ?: touchSceneLocalProps.getProperty(name)?.takeIf { it.isNotBlank() }

val originalPackage = "src/main/java/com/insta360/kmpsdk/demo"
val phoneSourceDirs = listOf(
    "src/mobile/java", "$originalPackage/touchscene", "$originalPackage/widget",
    "$originalPackage/ui/localimage", "$originalPackage/phonecamera",
)
val phoneTestDirs = listOf("src/test/java/com/insta360/kmpsdk/demo/touchscene")

android {
    namespace = "com.insta360.kmpsdk.demo"
    compileSdk = 35

    defaultConfig {
        // 独立安装，避免覆盖原影石 Demo 或上一轮手机版及其数据。
        applicationId = "com.touchscene.minimal"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = libs.versions.inskmpVersion.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 远程 AI 接口与 key：在 local.properties 或 gradle 命令行配置
        // touchscene.ai.endpoint / touchscene.ai.key，凭据不进版本库
        buildConfigField(
            "String",
            "TOUCHSCENE_AI_ENDPOINT",
            "\"${touchSceneProperty("touchscene.ai.endpoint") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "TOUCHSCENE_AI_KEY",
            "\"${touchSceneProperty("touchscene.ai.key") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "TOUCHSCENE_AI_MODEL",
            "\"${touchSceneProperty("touchscene.ai.model") ?: "qwen3.8-flash"}\"",
        )
        buildConfigField(
            "String",
            "TOUCHSCENE_AI_ASR_MODEL",
            "\"${touchSceneProperty("touchscene.ai.asrModel") ?: "qwen-audio-3.0-asr-flash"}\"",
        )

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            buildConfigField("String", "TOUCHSCENE_AI_ENDPOINT", "\"\"")
            buildConfigField("String", "TOUCHSCENE_AI_KEY", "\"\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    sourceSets.getByName("main") {
        // 厂商源码完整保留；手机版只编译原触觉模块及手机输入适配。
        assets.setSrcDirs(emptyList<String>())
        java.setSrcDirs(phoneSourceDirs)
    }
    sourceSets.getByName("test").java.setSrcDirs(phoneTestDirs)
    // lint 与当前 Kotlin UAST 工具链偶发不兼容导致 NonNullableMutableLiveDataDetector 崩溃，禁用该检测器以恢复 lintDebug
    lint {
        disable += "NullSafeMutableLiveData"
        // Only the two inherited hidden-control Orientation errors are baselined.
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }
}

kotlin.sourceSets.getByName("main").kotlin.setSrcDirs(phoneSourceDirs)
kotlin.sourceSets.getByName("test").kotlin.setSrcDirs(phoneTestDirs)


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.mlkit.image.labeling)
    testImplementation(libs.junit)
    // 让 JVM 单元测试能运行 android 同款 org.json 解析逻辑
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.immersionbar)
    implementation(libs.xx.permission)
    implementation(libs.timber)
    implementation(libs.glide)
    kapt(libs.glide.compiler)

    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2")

    // OpenCV：边缘(自适应阈值+轮廓)与轮廓(GrabCut)算法所需
    implementation(project(":opencv-sdk"))
}
