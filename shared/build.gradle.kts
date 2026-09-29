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
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
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
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.opencc4j)
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        val desktopMain by getting {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
                implementation("com.github.javakeyring:java-keyring:1.0.4")
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.security.crypto)
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
        }
        val jvmSharedTest by creating {
            dependsOn(commonTest.get())
            dependencies { implementation(libs.sqldelight.sqlite.driver) }
        }
        androidUnitTest.get().dependsOn(jvmSharedTest)
        getByName("desktopTest").dependsOn(jvmSharedTest)
    }
}

android {
    namespace = "me.thenano.yamibo.yamibo_app.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

tasks.withType<Test>().matching { it.name == "desktopTest" }.configureEach {
    systemProperty("yamibo.test.nativeKeyring", providers.gradleProperty("desktopNativeKeyringTest").orElse("false").get())
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("me.thenano.yamibo.yamibo_app")
            deriveSchemaFromMigrations.set(true)
        }
    }
}
