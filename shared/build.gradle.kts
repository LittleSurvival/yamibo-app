import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ksoup)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.yamibo.api)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.androidx.security.crypto)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.opencc4j)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

android {
    testOptions { unitTests.isReturnDefaultValues = true }
    namespace = "me.thenano.yamibo.yamibo_app.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("me.thenano.yamibo.yamibo_app")
            deriveSchemaFromMigrations.set(true)
        }
    }
}

// Validate the selected production checkout using this branch's existing tests.
val productionCheckout = providers.gradleProperty("productionCheckout").orNull
if (productionCheckout != null) {
    kotlin.sourceSets.getByName("commonMain").kotlin.setSrcDirs(listOf(
        "$productionCheckout/shared/src/commonMain/kotlin",
        "$productionCheckout/shared/build/generated/sqldelight/code/Database/commonMain",
    ))
    kotlin.sourceSets.getByName("androidMain").kotlin.setSrcDirs(listOf("$productionCheckout/shared/src/androidMain/kotlin"))
    tasks.configureEach { if (name == "generateCommonMainDatabaseInterface") enabled = false }
}
