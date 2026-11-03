import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

abstract class VerifyOpenMlsNativeCoverage : DefaultTask() {
    @get:InputFiles
    abstract val libraries: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val expected = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        val actual = libraries.files.filter { it.isFile }.map { it.parentFile.name }.toSet()
        check(actual == expected) { "OpenMLS native libraries must cover exactly the packaged ABIs: $expected; found $actual." }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
}

val focaaaLocalProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use { input -> load(input) }
    }
}

val releaseSigningNames = listOf(
    "FOCAAA_RELEASE_STORE_FILE",
    "FOCAAA_RELEASE_STORE_PASSWORD",
    "FOCAAA_RELEASE_KEY_ALIAS",
    "FOCAAA_RELEASE_KEY_PASSWORD"
)
val releaseSigningValues = releaseSigningNames.associateWith { name ->
    providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)
}
val releaseSigningEnabled = releaseSigningValues.values.any { it != null }
if (releaseSigningEnabled) {
    check(releaseSigningValues.values.all { it != null }) {
        "Set all four FOCAAA_RELEASE_* environment variables to sign a release."
    }
    check(!gradle.startParameter.isConfigurationCacheRequested) {
        "Signed releases must use --no-configuration-cache so signing passwords are not stored in Gradle's configuration cache."
    }
    check(rootProject.file(requireNotNull(releaseSigningValues["FOCAAA_RELEASE_STORE_FILE"])).isFile) {
        "The configured release keystore file does not exist."
    }
}

android {
    namespace = "com.example.focaaa"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.focaaa"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        val relayBaseUrl = focaaaLocalProperties.getProperty("focaaa.relayBaseUrl")
            ?: providers.gradleProperty("focaaa.relayBaseUrl").orNull.orEmpty()
        resValue("string", "relay_base_url", relayBaseUrl)
    }

    signingConfigs {
        if (releaseSigningEnabled) {
            create("privateRelease") {
                storeFile = rootProject.file(requireNotNull(releaseSigningValues["FOCAAA_RELEASE_STORE_FILE"]))
                storePassword = requireNotNull(releaseSigningValues["FOCAAA_RELEASE_STORE_PASSWORD"])
                keyAlias = requireNotNull(releaseSigningValues["FOCAAA_RELEASE_KEY_ALIAS"])
                keyPassword = requireNotNull(releaseSigningValues["FOCAAA_RELEASE_KEY_PASSWORD"])
            }
        }
    }
    buildTypes {
        release {
            if (releaseSigningEnabled) signingConfig = signingConfigs.getByName("privateRelease")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        resValues = true
    }

    sourceSets.getByName("main").jniLibs.directories.add(
        layout.buildDirectory.dir("generated/openmls/jniLibs").get().asFile.absolutePath
    )
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.applicationId.set("com.focaaa.app")
    }
}

tasks.register<Exec>("buildOpenMlsCompatibilityProbe") {
    group = "build"
    description = "Builds the optional OpenMLS Android compatibility probe for supported ABIs."
    workingDir(rootProject.projectDir.resolve("crypto/openmls"))
    val nativeOutput = layout.buildDirectory.dir("generated/openmls/jniLibs")
    inputs.files(fileTree(rootProject.file("crypto/openmls/src")) { include("**/*.rs") })
    inputs.file(rootProject.file("crypto/openmls/Cargo.toml"))
    inputs.file(rootProject.file("crypto/openmls/Cargo.lock"))
    val projectDirectoryPath = rootProject.projectDir.absolutePath
    val localPropertiesFile = rootProject.file("local.properties")
    val sdkFromLocalProperties = localPropertiesFile.takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
    }
    val androidSdkRoot = providers.environmentVariable("ANDROID_SDK_ROOT").orNull
    val androidHome = providers.environmentVariable("ANDROID_HOME").orNull
    val androidNdkHome = providers.environmentVariable("ANDROID_NDK_HOME").orNull
    val androidNdkRoot = providers.environmentVariable("ANDROID_NDK_ROOT").orNull
    outputs.dir(nativeOutput)
    doFirst {
        val cargoNdkCheck = try {
            ProcessBuilder("cargo", "ndk", "--version")
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            throw GradleException("OpenMLS spike requires cargo-ndk. Install it with `cargo install cargo-ndk`.", error)
        }
        val cargoNdkOutput = cargoNdkCheck.inputStream.bufferedReader().readText()
        if (cargoNdkCheck.waitFor() != 0) {
            throw GradleException(
                "OpenMLS spike requires cargo-ndk and an Android NDK. Install cargo-ndk and the NDK in Android Studio. $cargoNdkOutput"
            )
        }

        val sdkPath = androidSdkRoot
            ?: androidHome
            ?: sdkFromLocalProperties
        val configuredNdk = androidNdkHome ?: androidNdkRoot
        val ndkPath = configuredNdk?.let {
            val candidate = File(it).let { path -> if (path.isAbsolute) path else File(projectDirectoryPath, it) }
            candidate.takeIf { path -> path.isDirectory }
        } ?: sdkPath?.let { File(it) }?.resolve("ndk")?.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.name }
        if (ndkPath == null) {
            throw GradleException(
                "OpenMLS spike requires the Android NDK. Install it from Android Studio > SDK Manager > SDK Tools, " +
                    "or set ANDROID_NDK_HOME to an installed NDK directory."
            )
        }
        environment("ANDROID_NDK_HOME", ndkPath.absolutePath)

        val targetCheck = try {
            ProcessBuilder("rustup", "target", "list", "--installed")
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            throw GradleException("OpenMLS spike requires rustup with Android Rust targets installed.", error)
        }
        val targetOutput = targetCheck.inputStream.bufferedReader().readText()
        if (targetCheck.waitFor() != 0) {
            throw GradleException("Could not query installed Rust targets. $targetOutput")
        }
        val installedTargets = targetOutput.lineSequence().map { it.trim() }.toSet()
        val requiredTargets = listOf(
            "aarch64-linux-android",
            "armv7-linux-androideabi",
            "x86_64-linux-android"
        )
        val missingTargets = requiredTargets.filterNot(installedTargets::contains)
        if (missingTargets.isNotEmpty()) {
            throw GradleException(
                "OpenMLS spike is missing Rust targets: ${missingTargets.joinToString()}. " +
                    "Install them with `rustup target add ${missingTargets.joinToString(" ")}`."
            )
        }
    }
    commandLine(
        "cargo",
        "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", layout.buildDirectory.dir("generated/openmls/jniLibs").get().asFile.absolutePath,
        "build",
        "--release"
    )
}

tasks.register<VerifyOpenMlsNativeCoverage>("verifyOpenMlsNativeCoverage") {
    dependsOn(tasks.named("buildOpenMlsCompatibilityProbe"))
    libraries.from(listOf("arm64-v8a", "armeabi-v7a", "x86_64").map { abi ->
        layout.buildDirectory.file("generated/openmls/jniLibs/$abi/libfocaaa_openmls.so")
    })
}

// Package the JNI library in every app build so the real pairing flow cannot ship without it.
tasks.named("preBuild").configure {
    dependsOn(tasks.named("verifyOpenMlsNativeCoverage"))
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    debugImplementation(libs.firebase.appcheck.debug)
    releaseImplementation(libs.firebase.appcheck.recaptcha)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    implementation(libs.okhttp)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
