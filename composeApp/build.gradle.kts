import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
    id("local.i18n-auto-merge")
}

val yamiboAppVersionCode = 9
val yamiboAppVersionName = "0.0.8"
val yamiboAppApplicationId = "me.thenano.yamibo.yamibo_app"
val generatedDebugWafResources = layout.buildDirectory.dir("generated/wafSimulatorResources/debug")
val localProperties = Properties().apply {
    val file = rootProject.layout.projectDirectory.file("local.properties").asFile
    if (file.isFile) {
        file.inputStream().use(::load)
    }
}
val iosIdentifiers = Properties().apply {
    val file = rootProject.layout.projectDirectory
        .file("iosApp/Configuration/AppIdentifiers.xcconfig")
        .asFile
    require(file.isFile) { "Missing iOS identifier configuration: ${file.path}" }
    file.inputStream().use(::load)
}
val yamiboIosBundleId = iosIdentifiers.getProperty("YAMIBO_IOS_BUNDLE_ID")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: error("YAMIBO_IOS_BUNDLE_ID must be set in iosApp/Configuration/AppIdentifiers.xcconfig")

fun localProperty(name: String): String? =
    localProperties.getProperty(name)?.takeIf { it.isNotBlank() }

val debugWafEnvironment =
    localProperty("debugWafEnvironment")?.toBooleanStrictOrNull() ?: false

val releaseRunSigningValues = listOf(
    localProperty("yamibo.releaseRun.storeFile"),
    localProperty("yamibo.releaseRun.storePassword"),
    localProperty("yamibo.releaseRun.keyAlias"),
    localProperty("yamibo.releaseRun.keyPassword"),
)
val hasReleaseRunSigning = releaseRunSigningValues.all { it != null }
val useReleaseSignatureForDebugRun = localProperty("yamibo.debug.useReleaseSignature") == "true"
val yamiboAppReleaseRunApplicationId =
    if (hasReleaseRunSigning) yamiboAppApplicationId else "$yamiboAppApplicationId.run"
require(releaseRunSigningValues.all { it == null } || hasReleaseRunSigning) {
    "releaseRun signing requires all local.properties keys: " +
        "yamibo.releaseRun.storeFile, yamibo.releaseRun.storePassword, " +
        "yamibo.releaseRun.keyAlias, yamibo.releaseRun.keyPassword"
}
require(!useReleaseSignatureForDebugRun || hasReleaseRunSigning) {
    "yamibo.debug.useReleaseSignature=true requires releaseRun signing keys in local.properties"
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
    applyDefaultHierarchyTemplate()

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            binaryOption("bundleId", yamiboIosBundleId)
        }
    }

    sourceSets {
        val jvmSharedMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmSharedMain)
        getByName("desktopMain").dependsOn(jvmSharedMain)
        val generatedRestorableRegistryDir = layout.buildDirectory.dir("generated/restorableScreenRegistry/commonMain/kotlin")
        val generatedI18nKotlinDir = layout.buildDirectory.dir("generated/i18n/kotlin")
        val generatedAppVersionKotlinDir = layout.buildDirectory.dir("generated/appVersion/commonMain/kotlin")
        val generatedYamiboIconsDir = layout.buildDirectory.dir("generated/yamiboIcons/commonMain/kotlin")
        commonMain {
            kotlin.srcDir(generatedRestorableRegistryDir)
            kotlin.srcDir(generatedI18nKotlinDir)
            kotlin.srcDir(generatedAppVersionKotlinDir)
            kotlin.srcDir(generatedYamiboIconsDir)
            resources.exclude("assets/icons/**")
        }

        androidMain.dependencies {
            implementation(libs.coil3.gif)
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.work.runtime.ktx)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(compose.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.coil3.compose)
            implementation(libs.coil3.network.ktor3)
            implementation(libs.coil3.svg)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.yamibo.api)
            implementation(libs.ksoup)
            implementation(projects.shared)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinx.coroutines.get()}")
        }
        getByName("desktopTest").dependencies { implementation(compose.desktop.uiTestJUnit4) }
        val desktopMain by getting {
            resources.srcDir(tasks.register<Sync>("prepareDesktopIcon") {
                from("src/androidMain/res/mipmap-xxxhdpi") {
                    include("ic_launcher.png")
                    rename { "yamibo-icon.png" }
                }
                into(layout.buildDirectory.dir("generated/desktopIcon"))
            })
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.kotlinx.coroutines.get()}")
                implementation("me.friwi:jcefmaven:146.0.10")
                implementation("net.java.dev.jna:jna-platform:5.13.0")
            }
        }
    }
}

val desktopPackageResources = layout.buildDirectory.dir("generated/desktopPackageResources")
val desktopPackageIcons = layout.buildDirectory.dir("generated/desktopPackageIcons")
val prepareDesktopPackageIcons by tasks.registering {
    val source = layout.projectDirectory.file("src/androidMain/res/mipmap-xxxhdpi/ic_launcher.png")
    val iconOutput = desktopPackageIcons.get().asFile
    inputs.file(source)
    outputs.dir(desktopPackageIcons)
    doLast {
        val output = iconOutput.apply { mkdirs() }
        val original = ImageIO.read(source.asFile)
        val scaled = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().let { graphics ->
            try { graphics.drawImage(original, 0, 0, 256, 256, null) } finally { graphics.dispose() }
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(scaled, "png", it) }.toByteArray()
        output.resolve("yamibo.png").writeBytes(png)
        val ico = ByteBuffer.allocate(22 + png.size).order(ByteOrder.LITTLE_ENDIAN)
        ico.putShort(0).putShort(1).putShort(1).put(0).put(0).put(0).put(0)
            .putShort(1).putShort(32).putInt(png.size).putInt(22).put(png)
        output.resolve("yamibo.ico").writeBytes(ico.array())
        val icns = ByteBuffer.allocate(16 + png.size).order(ByteOrder.BIG_ENDIAN)
        icns.put("icns".toByteArray()).putInt(16 + png.size).put("ic08".toByteArray()).putInt(8 + png.size).put(png)
        output.resolve("yamibo.icns").writeBytes(icns.array())
    }
}
val prepareDesktopBrowser by tasks.registering(JavaExec::class) {
    dependsOn("desktopMainClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles!!
    mainClass.set("me.thenano.yamibo.yamibo_app.DesktopMainKt")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8")
    val output = desktopPackageResources.map { it.dir("common/chromium-146.0.10") }
    outputs.dir(output)
    args("--prepare-browser", output.get().asFile.absolutePath)
}

compose.desktop {
    application {
        mainClass = "me.thenano.yamibo.yamibo_app.DesktopMainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Yamibo"
            // Monotonic installer version, independent of the displayed semantic version.
            require(yamiboAppVersionCode in 1..65535)
            packageVersion = "1.0.$yamiboAppVersionCode"
            description = "Yamibo forum client"
            vendor = "TheNano"
            includeAllModules = true
            appResourcesRootDir.set(desktopPackageResources)
            windows {
                iconFile.set(desktopPackageIcons.map { it.file("yamibo.ico") })
                upgradeUuid = "ba5a6164-7b43-46ad-b534-c0ecbdd937a7"
                perUserInstall = true
                menu = true
                shortcut = true
                menuGroup = "Yamibo"
            }
            macOS {
                iconFile.set(desktopPackageIcons.map { it.file("yamibo.icns") })
                bundleID = "me.thenano.yamibo.desktop"
            }
            linux {
                iconFile.set(desktopPackageIcons.map { it.file("yamibo.png") })
                packageName = "yamibo"
                shortcut = true
            }
        }
        jvmArgs += listOf("--add-opens=java.desktop/sun.awt=ALL-UNNAMED")
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8")
        if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
            jvmArgs += listOf(
                "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            )
        }
    }
}

tasks.matching { it.name in setOf("prepareAppResources", "createDistributable", "packageMsi", "packageDmg", "packageDeb", "packageRpm") }.configureEach {
    dependsOn(prepareDesktopPackageIcons, prepareDesktopBrowser)
}

tasks.withType<Test>().matching { it.name == "desktopTest" }.configureEach {
    systemProperty("yamibo.test.nativeBrowser", providers.gradleProperty("desktopNativeBrowserTest").orElse("false").get())
    jvmArgs("--add-opens=java.desktop/sun.awt=ALL-UNNAMED")
    if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        jvmArgs(
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )
    }
}

i18nAutoMerge {
    scanDirs.set(listOf("composeApp/src", "shared/src"))
    glossary.set(rootProject.layout.projectDirectory.file("i18n/glossary.csv"))
    baseTranslations.set(rootProject.layout.projectDirectory.file("i18n/base.csv"))
    composeAssetResourcesDir.set(rootProject.layout.projectDirectory.dir("composeApp/src/commonMain/composeResources"))
    outputComposeResources.set(layout.buildDirectory.dir("generated/i18n/composeResources"))
    outputKotlin.set(layout.buildDirectory.dir("generated/i18n/kotlin"))
    reportDir.set(layout.buildDirectory.dir("reports/i18n"))
    defaultLanguage.set("zh-tw")
    fallbackToSource.set(true)
    failOnPlaceholderMismatch.set(true)
    failOnMissingTranslation.set(false)
    apiFunctionName.set("i18n")
    runtimePackage.set("me.thenano.yamibo.yamibo_app.i18n")
    resImportPackage.set("yamibo_app.composeapp.generated.resources")
}

val generateRestorableScreenRegistry by tasks.registering(GenerateRestorableScreenRegistryTask::class) {
    description = ""
    val sourceDir = layout.projectDirectory.dir("src/commonMain/kotlin")
    this.sourceDir.set(sourceDir)
    outputFile.set(layout.buildDirectory.file("generated/restorableScreenRegistry/commonMain/kotlin/me/thenano/yamibo/yamibo_app/navigation/GeneratedRestorableScreenRegistry.kt"))
}

val generateAppVersion by tasks.registering(GenerateAppVersionTask::class) {
    description = "Generates AppVersion.kt from update/manifest.json."
    manifestFile.set(rootProject.layout.projectDirectory.file("update/manifest.json"))
    outputFile.set(layout.buildDirectory.file("generated/appVersion/commonMain/kotlin/me/thenano/yamibo/yamibo_app/AppVersion.kt"))
}

val yamiboIconsSourcePath = providers.gradleProperty("yamibo.icons.sourceDir")
    .orElse("composeApp/src/commonMain/resources/assets/icons")
    .get()
val yamiboIconsSourceDirectory = rootProject.file(yamiboIconsSourcePath)

val generateYamiboIcons by tasks.registering(GenerateYamiboIconsTask::class) {
    description = "Generates YamiboIcons.kt from SVG source files."
    sourceDir.set(yamiboIconsSourceDirectory)
    outputFile.set(layout.buildDirectory.file("generated/yamiboIcons/commonMain/kotlin/YamiboIcons.kt"))
}

val stageComposeResources by tasks.registering(Sync::class) {
    description = "Stages generated Compose resources and bundled changelogs."
    dependsOn("generateI18nResources")
    from(layout.buildDirectory.dir("generated/i18n/composeResources"))
    from(
        listOf(1, yamiboAppVersionCode)
            .distinct()
            .map { versionCode ->
                rootProject.layout.projectDirectory.file("update/changelogs/$versionCode.changelog")
            },
    ) {
        into("files/changelogs")
    }
    into(layout.buildDirectory.dir("generated/composeResources"))
}

compose.resources {
    customDirectory(
        "commonMain",
        layout.dir(stageComposeResources.map { task -> task.destinationDir }),
    )
}

tasks.matching { task ->
    task.name.startsWith("compile") && task.name.contains("Kotlin")
}.configureEach {
    dependsOn(generateRestorableScreenRegistry)
    dependsOn(generateAppVersion)
    dependsOn(generateYamiboIcons)
    dependsOn(stageComposeResources)
}

android {
    namespace = "me.thenano.yamibo.yamibo_app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = yamiboAppApplicationId
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = yamiboAppVersionCode
        versionName = yamiboAppVersionName
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    signingConfigs {
        if (hasReleaseRunSigning) {
            create("releaseRunLocal") {
                storeFile = rootProject.file(requireNotNull(localProperty("yamibo.releaseRun.storeFile")))
                storePassword = requireNotNull(localProperty("yamibo.releaseRun.storePassword"))
                keyAlias = requireNotNull(localProperty("yamibo.releaseRun.keyAlias"))
                keyPassword = requireNotNull(localProperty("yamibo.releaseRun.keyPassword"))
            }
        }
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["debugNetworkSecurityConfig"] = if (debugWafEnvironment) {
                "@xml/debug_waf_network_security_config"
            } else {
                "@xml/debug_default_network_security_config"
            }
            if (useReleaseSignatureForDebugRun) {
                signingConfig = signingConfigs.getByName("releaseRunLocal")
            } else {
                applicationIdSuffix = ".debug"
                versionNameSuffix = "-debug"
            }
        }
        getByName("release") { isMinifyEnabled = false }
        create("releaseRun") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName(if (hasReleaseRunSigning) "releaseRunLocal" else "debug")
            if (!hasReleaseRunSigning) {
                applicationIdSuffix = ".run"
                versionNameSuffix = "-run"
            }
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    if (debugWafEnvironment) {
        sourceSets.getByName("debug").res.srcDir(generatedDebugWafResources)
    }
}

dependencies { debugImplementation(compose.uiTooling) }

fun localSdkDir(): String? {
    return localProperty("sdk.dir")
}

fun adbExecutablePath(): String {
    val adbName = if (isWindowsHost) "adb.exe" else "adb"
    val sdkDir = localSdkDir()
    if (!sdkDir.isNullOrBlank()) {
        val adb = File(sdkDir, "platform-tools/$adbName")
        if (adb.isFile) return adb.absolutePath
    }
    return adbName
}

tasks.register<Exec>("runReleaseOnDevice") {
    group = "run"
    description = "Installs the non-debuggable releaseRun APK, then launches the app on the selected adb device."
    notCompatibleWithConfigurationCache("Launches the app through the local adb executable after installReleaseRun.")
    dependsOn("installReleaseRun")
    isIgnoreExitValue = false
    doFirst {
        commandLine(
            adbExecutablePath(),
            "shell",
            "monkey",
            "-p",
            yamiboAppReleaseRunApplicationId,
            "-c",
            "android.intent.category.LAUNCHER",
            "1",
        )
    }
}

val yamiboDebugApplicationId = if (useReleaseSignatureForDebugRun) {
    yamiboAppApplicationId
} else {
    "$yamiboAppApplicationId.debug"
}
val wafSimulatorStartScriptPath = rootProject.file("tools/waf-405-simulator/start.ps1").absolutePath
val wafSimulatorLaunchScriptPath = rootProject.file("tools/waf-405-simulator/launch.ps1").absolutePath
val wafSimulatorStopScriptPath = rootProject.file("tools/waf-405-simulator/stop.ps1").absolutePath
val generatedDebugWafResourcesPath = generatedDebugWafResources.get().asFile.absolutePath

val prepareDebugWafEnvironment = tasks.register<Exec>("prepareDebugWafEnvironment") {
    group = "build setup"
    description = "Generates the local proxy CA resources for a WAF simulator debug build."
    notCompatibleWithConfigurationCache("Bootstraps a local mitmproxy runtime and CA.")
    commandLine(
        "powershell.exe",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        wafSimulatorStartScriptPath,
        "-PrepareOnly",
        "-GeneratedResourceDirectory",
        generatedDebugWafResourcesPath,
    )
}

if (debugWafEnvironment) {
    tasks.matching { it.name == "preDebugBuild" }.configureEach {
        dependsOn(prepareDebugWafEnvironment)
    }
}

tasks.register<Exec>("runDebugWafEnvironment") {
    group = "run"
    description = "Installs debug, starts the emulator HTTP 405 simulator, and launches the app."
    notCompatibleWithConfigurationCache("Starts a local proxy and changes emulator proxy settings.")
    val wafEnvironmentEnabled = debugWafEnvironment
    if (wafEnvironmentEnabled) {
        dependsOn("installDebug")
    }
    commandLine(
        "powershell.exe",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        wafSimulatorLaunchScriptPath,
        "-ApplicationId",
        yamiboDebugApplicationId,
    )
    doFirst {
        require(wafEnvironmentEnabled) {
            "runDebugWafEnvironment requires debugWafEnvironment=true in local.properties"
        }
    }
}

tasks.register<Exec>("startDebugWafEnvironmentForIde") {
    group = "run"
    description = "Starts only the emulator HTTP 405 simulator and proxy for an IDE Android debug run."
    notCompatibleWithConfigurationCache("Starts a local proxy and changes emulator proxy settings.")
    val wafEnvironmentEnabled = debugWafEnvironment
    commandLine(
        "powershell.exe",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        wafSimulatorLaunchScriptPath,
        "-ProxyOnly",
    )
    doFirst {
        require(wafEnvironmentEnabled) {
            "startDebugWafEnvironmentForIde requires debugWafEnvironment=true in local.properties"
        }
    }
}

tasks.register<Exec>("stopDebugWafEnvironment") {
    group = "run"
    description = "Stops the emulator HTTP 405 simulator and restores normal proxy settings."
    notCompatibleWithConfigurationCache("Stops a local proxy and changes emulator proxy settings.")
    commandLine(
        "powershell.exe",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        wafSimulatorStopScriptPath,
    )
}

val syncStableManifest by tasks.registering(SyncStableManifestTask::class) {
    group = "release"
    description = "Copies update/manifest.json to update/stable.json. manifest.json is the only manually edited update manifest."
    manifestFile.set(rootProject.layout.projectDirectory.file("update/manifest.json"))
    stableFile.set(rootProject.layout.projectDirectory.file("update/stable.json"))
}

val validateUpdateManifest by tasks.registering(ValidateUpdateManifestTask::class) {
    group = "verification"
    description = "Fails the build when app version, update manifests, or changelog are inconsistent."
    dependsOn(syncStableManifest)
    manifestFile.set(rootProject.layout.projectDirectory.file("update/manifest.json"))
    stableFile.set(rootProject.layout.projectDirectory.file("update/stable.json"))
    changelogsDir.set(rootProject.layout.projectDirectory.dir("update/changelogs"))
    appVersionCode.set(yamiboAppVersionCode.toLong())
    appVersionName.set(yamiboAppVersionName)
}

val validatePublishedUpdateManifest by tasks.registering(ValidatePublishedUpdateManifestTask::class) {
    group = "verification"
    description = "Validates a generated published update manifest with isReady=true."
    publishedUpdateDirPath.set(providers.gradleProperty("publishedUpdateDir")
        .orElse(layout.buildDirectory.dir("published-update/update").map { it.asFile.absolutePath })
    )
}

tasks.named("check") {
    dependsOn(validateUpdateManifest)
}

val enableAutoUnlockResources = false
val isWindowsHost = System.getProperty("os.name").contains("windows", ignoreCase = true)
val unlockScriptFile = rootProject.layout.projectDirectory.file("unlock_build.ps1").asFile

val autoUnlockTask = tasks.register<Exec>("autoUnlockResources") {
    group = "build setup"
    description = "Optionally unlocks Windows build artifacts before Android resource tasks."
    enabled = enableAutoUnlockResources
    isIgnoreExitValue = true

    if (isWindowsHost && unlockScriptFile.exists()) {
        commandLine(
            "powershell.exe",
            "-ExecutionPolicy",
            "Bypass",
            "-NoProfile",
            "-File",
            unlockScriptFile.absolutePath,
            "-TargetDir",
            rootProject.rootDir.absolutePath,
        )
    } else if (isWindowsHost) {
        commandLine("cmd", "/c", "echo autoUnlockResources script is missing; skipping")
    } else {
        commandLine("sh", "-c", "echo autoUnlockResources is Windows-only; skipping")
    }
}

tasks.configureEach {
    if (name == "processDebugResources" || name == "processReleaseResources" || name == "mergeDebugResources") {
        dependsOn(autoUnlockTask)
    }
}
