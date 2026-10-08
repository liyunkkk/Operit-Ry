import java.io.File
import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

abstract class BundleAccessibilityProvider : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val providerApk: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val source = providerApk.get().asFile
        check(source.isFile) { "A signed accessibility provider APK is required." }
        val output = outputDirectory.get().asFile
        output.mkdirs()
        source.copyTo(File(output, "operit-ry-accessibility.apk"), overwrite = true)
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
}

val verifyScriptExecutionReceiverManifests =
    tasks.register("verifyScriptExecutionReceiverManifests") {
        group = "verification"
        description =
            "Verifies the merged ScriptExecutionReceiver permission policy for every app build type."
        dependsOn(
            "processDebugMainManifest",
            "processCloneMainManifest",
            "processReleaseMainManifest",
            "processNightlyMainManifest"
        )

        doLast {
            val androidNamespace = "http://schemas.android.com/apk/res/android"
            val receiverClass =
                "com.ai.assistance.operit.core.tools.javascript.ScriptExecutionReceiver"
            val expectations =
                listOf(
                    arrayOf(
                        "debug",
                        "true",
                        "android.permission.DUMP",
                        "com.rainy.operitry"
                    ),
                    arrayOf(
                        "clone",
                        "true",
                        "com.rainy.operitry.clone.permission.EXECUTE_JS",
                        "com.rainy.operitry.clone"
                    ),
                    arrayOf(
                        "release",
                        "true",
                        "com.rainy.operitry.permission.EXECUTE_JS",
                        "com.rainy.operitry"
                    ),
                    arrayOf(
                        "nightly",
                        "true",
                        "com.rainy.operitry.permission.EXECUTE_JS",
                        "com.rainy.operitry"
                    )
                )

            expectations.forEach {
                    (variant, expectedExported, expectedPermission, expectedPackage) ->
                val taskSuffix = variant.replaceFirstChar(Char::uppercase)
                val manifestFile =
                    layout.buildDirectory
                        .file(
                            "intermediates/merged_manifest/$variant/" +
                                "process${taskSuffix}MainManifest/AndroidManifest.xml"
                        )
                        .get()
                        .asFile
                check(manifestFile.isFile) {
                    "Merged manifest does not exist: ${'$'}{manifestFile.absolutePath}"
                }
                val document =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance()
                        .apply { isNamespaceAware = true }
                        .newDocumentBuilder()
                        .parse(manifestFile)
                val actualPackage = document.documentElement.getAttribute("package")
                check(actualPackage == expectedPackage) {
                    "$variant package=$actualPackage, expected $expectedPackage"
                }
                val receivers = document.getElementsByTagName("receiver")
                val matchingReceivers =
                    (0 until receivers.length)
                        .map { receivers.item(it) as org.w3c.dom.Element }
                        .filter {
                            it.getAttributeNS(androidNamespace, "name") == receiverClass
                        }
                check(matchingReceivers.size == 1) {
                    "$variant must contain exactly one $receiverClass receiver"
                }
                val receiver = matchingReceivers.single()
                val actualExported = receiver.getAttributeNS(androidNamespace, "exported")
                val actualPermission = receiver.getAttributeNS(androidNamespace, "permission")
                check(actualExported == expectedExported) {
                    "$variant exported=$actualExported, expected $expectedExported"
                }
                check(actualPermission == expectedPermission) {
                    "$variant permission=$actualPermission, expected $expectedPermission"
                }
            }
        }
    }

tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(verifyScriptExecutionReceiverManifests)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}
androidComponents.onVariants { variant ->
    val providerType = if (variant.buildType in setOf("release", "nightly")) "release" else "debug"
    val providerTask = providerType.replaceFirstChar(Char::uppercase)
    val suffix = variant.name.replaceFirstChar(Char::uppercase)
    val bundle = tasks.register<BundleAccessibilityProvider>("bundle${suffix}AccessibilityProvider") {
        dependsOn(":accessibility-provider:assemble$providerTask")
        providerApk.set(project(":accessibility-provider").layout.buildDirectory.file(
            "outputs/apk/$providerType/accessibility-provider-$providerType.apk"))
        outputDirectory.set(layout.buildDirectory.dir("generated/accessibilityAssets/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(bundle, BundleAccessibilityProvider::outputDirectory)
}

android {
    androidResources {
        // Exact asset names, including assets merged from the terminal library.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13:ubuntu-noble-aarch64-pd-v4.18.0.tar.xz:android.apk:windows.zip"
    }
    namespace = "com.ai.assistance.operit"
    compileSdk = 36

    signingConfigs {
        val releaseKeystorePath = localProperties.getProperty("RELEASE_STORE_FILE")
        val releaseStorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
        val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
        val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")

        if (releaseKeystorePath != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null &&
            File(releaseKeystorePath).exists()
        ) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    defaultConfig {
        applicationId = "com.rainy.operitry"
        minSdk = 26
        targetSdk = 34
        versionCode = 100205
        versionName = "1.12.2-ry.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        ndk {
            // Explicitly specify the ABIs we package for the app process.
            // terminal now also ships x86_64 runtime binaries for the Android Studio emulator,
            // while the rest of the app remains primarily ARM-focused.
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }

        buildConfigField("boolean", "PERSONAL_DEV_UPDATE_CHANNEL", "false")
    }

    buildTypes {
        val releaseSigningConfig = signingConfigs.findByName("release")

        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
        debug {
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
        create("clone") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".clone"
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "Operit Ry Clone")
        }
        create("nightly") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
            matchingFallbacks += listOf("release")
        }
    }
    applicationVariants.all {
        if (buildType.name == "nightly") {
            outputs.all {
                val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                output.outputFileName = "app-nightly.apk"
            }
        }
        if (buildType.name == "clone") {
            outputs.all {
                val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                output.outputFileName = "app-clone.apk"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    lint {
        baseline = file("lint-baseline.xml")
        checkDependencies = true
        disable += "MissingTranslation"
    }

    packaging {
        
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE-EPL-1.0.txt"
            excludes += "LICENSE-EPL-1.0.txt"
            excludes += "/META-INF/LICENSE-EDL-1.0.txt"
            excludes += "LICENSE-EDL-1.0.txt"
            
            // Resolve merge conflicts for document libraries
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "META-INF/versions/9/module-info.class"
            
            // Fix for duplicate Netty files
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/INDEX.LIST"
            
            // Fix for any other potential duplicate files
            pickFirsts += "**/*.so"
        }
    }
//    aaptOptions {
//        noCompress += "tflite"
//    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":chat-parser"))
    implementation(project(":chat-storage"))
    implementation(project(":memory-storage"))
    val objectBoxTestPlatform = when {
        System.getProperty("os.name").contains("Windows", ignoreCase = true) -> "windows"
        System.getProperty("os.name").contains("Mac", ignoreCase = true) -> "macos"
        System.getProperty("os.name").contains("Linux", ignoreCase = true) -> "linux"
        else -> null
    }
    objectBoxTestPlatform?.let { platform ->
        testRuntimeOnly("io.objectbox:objectbox-$platform:${libs.versions.objectbox.get()}")
    }
    implementation("com.github.jelmerk:hnswlib-core:1.2.1")
    implementation(project(":dragonbones"))
    implementation(project(":terminal"))
    implementation(project(":mmd"))
    implementation(project(":fbx"))
    implementation(project(":showerclient"))
    implementation(project(":quickjs"))

    // glTF runtime rendering (Filament)
    implementation("com.google.android.filament:filament-android:1.69.2")
    implementation("com.google.android.filament:gltfio-android:1.69.2")
    implementation("com.google.android.filament:filament-utils-android:1.69.2")
    implementation(libs.androidx.ui.graphics.android)
    // Vendored binary dependencies live in app/libs, including ffmpeg-kit and its Java-side deps.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation(libs.androidx.runtime.android)
    implementation(libs.androidx.ui.text.android)
    implementation(libs.androidx.animation.android)
    implementation(libs.androidx.ui.android)
    implementation(libs.androidx.activity.ktx)

    // Desugaring support for modern Java APIs on older Android
    coreLibraryDesugaring(libs.desugar.jdk)

    // ML Kit - 文本识别
    implementation(libs.mlkit.text.recognition)
    // ML Kit - 多语言识别支持
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.korean)
    
    implementation(libs.zxing.core)
    
    // diff
    implementation(libs.java.diff.utils)
    
    // APK解析和修改库
    implementation(libs.android.apksig) // APK签名工具
    implementation(libs.apk.parser) // 用于解析和处理AndroidManifest.xml
    implementation(libs.sable.axml) // 用于Android二进制XML的读写
    implementation(libs.zipalign.java) // 用于处理ZIP文件对齐
    
    // ZIP处理库 - 用于APK解压和重打包
    implementation(libs.commons.compress)
    implementation(libs.commons.io) // 添加Apache Commons IO
    
    // 图片处理库
    implementation(libs.glide) // 用于处理图像
    
    // XML处理
    implementation(libs.androidx.core.ktx)
    
    // libsu - root access library
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("com.github.topjohnwu.libsu:nio:6.0.0")
    
    // Add missing SVG support
    implementation(libs.androidsvg)
    
    // Add missing GIF support for Markwon
    implementation(libs.android.gif)
    
    // Image Cropper for background image cropping
    implementation(libs.image.cropper)
    
    // ExoPlayer for video background
    implementation(libs.exoplayer)
    implementation(libs.exoplayer.core)
    implementation(libs.exoplayer.ui)
    
    // Material 3 Window Size Class
    implementation(libs.material3.window)
    
    // Window metrics library for foldables and adaptive layouts
    implementation(libs.window)
    implementation(libs.androidx.webkit)

    // Document conversion libraries
    implementation(libs.itextg)
    implementation(libs.pdfbox)
    implementation(libs.zip4j)
    
    // 图片加载库
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    
    // LaTeX rendering libraries
    implementation(libs.jlatexmath)
    implementation(libs.renderx) // RenderX library for LaTeX rendering
    
    // Base Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlin.reflect)
    
    // UUID dependencies
    implementation(libs.uuid)
    
    // Gson for JSON parsing
    implementation(libs.gson)

    // HJSON dependency for human-friendly JSON parsing
    implementation(libs.hjson)

    // 中文分词库 - Jieba Android
    implementation(libs.jieba)

    // 向量搜索库 - 轻量级实现，适合Android
    implementation(libs.hnswlib.utils)
    
    // ONNX Runtime is required by VITS speech synthesis and Silero VAD.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")

    // Room 数据库
    implementation(libs.room.runtime)
    implementation(libs.room.ktx) // Kotlin扩展和协程支持

    // ObjectBox
    implementation(libs.objectbox.kotlin)
    implementation(libs.junrar)

    // Compose dependencies - use BOM for version consistency
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    // Use BOM version for all Compose dependencies
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.core)

    // Navigation Compose
    implementation(libs.navigation.compose)

    // Shizuku dependencies
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Tasker Plugin Library
    implementation("com.joaomgcd:taskerpluginlibrary:0.4.10")
    
    // WorkManager for scheduled workflows
    implementation(libs.work.runtime.ktx)

    // Network dependencies
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.jsoup)

    // DataStore dependencies
    implementation(libs.datastore.preferences)
    implementation(libs.datastore.preferences.core)

    // Debug dependencies
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Test dependencies
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.compose.bom))

    // JVM 上的真实 Room 迁移/DAO 测试：Android Room 2.8 生成的实现基于
    // androidx.sqlite KMP 接口，用 sqlite-jdbc 实现纯 JVM 驱动（见测试支撑类
    // JdbcSQLiteDriver），仅在单元测试使用。
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")

    // 单元测试中真实 org.json（Android 桩在 JVM 测试里会抛 Stub! 异常）；
    // 统计 usage 归一化测试需要解析 JSONObject。
    testImplementation("org.json:json:20240303")

    // 入口级恢复测试：RawSnapshotBackupManager 内部使用 Dispatchers.Main 汇报
    // 进度，JVM 测试用 setMain 安装测试主调度器。
    testImplementation(libs.coroutines.test)

    // Apache POI - for Document processing (DOC, DOCX, etc.)
    implementation(libs.poi)
    implementation(libs.poi.ooxml)
    implementation(libs.poi.scratchpad)

    // Kotlin logging
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.simple)

    // Color picker for theme customization
    implementation(libs.colorpicker)
    implementation(libs.backdrop)
    implementation(libs.liquid)
    
    // NanoHTTPD for local web server
    implementation(libs.nanohttpd)

    // 添加测试依赖
    testImplementation(libs.junit)
    
    // Android测试依赖
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    
    // 协程测试依赖
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.coroutines.test)
    
    // 模拟测试框架 - 保留现有的 mockito 并新增 mockk
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.mockito.android)
    
    // // 新增的测试依赖 - mockk 和 kotlin-test
    // testImplementation(libs.mockk)
    // testImplementation(libs.ktor.server.test.host)
    // testImplementation(libs.kotlinx.coroutines.debug)
    // androidTestImplementation(libs.mockk)
    
    implementation(libs.reorderable)

    // Swipe to reveal actions
    implementation(libs.swipe)

    // Coroutine
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    implementation("io.modelcontextprotocol.sdk:mcp:1.1.0")
    
    // Exclude bcprov-jdk15to18 from all configurations to avoid duplicate classes
    configurations.all {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // BouncyCastle - explicitly include jdk18on version to avoid conflicts
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")

    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")


    // Accompanist
    implementation("com.google.accompanist:accompanist-systemuicontroller:0.32.0")

    // Glance for Widgets (Compose for Widgets)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
}

// The workflow receiver security test validates dependency-contributed Tasker components in the
// actual merged manifest, so keep that artifact fresh whenever the debug JVM suite runs.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn("processDebugMainManifest")
    dependsOn(":chat-parser:test")
}
