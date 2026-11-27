import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing, fail-closed.
 *
 * A release artifact is signed only with a real release key, supplied through
 * `keystore/keystore.properties` at the repository root (storeFile, storePassword,
 * keyAlias, keyPassword) or, for a CI checkout, through the environment:
 *
 *   HASHES_KEYSTORE_FILE, HASHES_KEYSTORE_PASSWORD, HASHES_KEY_ALIAS, HASHES_KEY_PASSWORD
 *
 * The file is never committed. With none of the values set, a task that writes a
 * release artifact stops here instead of writing one unsigned: an artifact that
 * cannot install over the app must not look shippable. The debug key is never used
 * as a fallback, because an APK signed with it would install over nobody's app.
 *
 * A builder that signs the artifact with its own key - F-Droid runs
 * `assembleRelease` in a checkout with no keystore and signs afterwards - asks for
 * an unsigned artifact explicitly with `-Phashes.unsignedRelease=true`. The flag is
 * the only way a release build runs without signing material; nothing infers it.
 * Code checks (unit tests, lint, dependency reports) never need the key.
 */
val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(propertyKey: String, environmentKey: String): String? =
    keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "HASHES_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "HASHES_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "HASHES_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "HASHES_KEY_PASSWORD")

val missingReleaseSigning = buildList {
    if (releaseStoreFile == null) add("storeFile (HASHES_KEYSTORE_FILE)")
    if (releaseStorePassword == null) add("storePassword (HASHES_KEYSTORE_PASSWORD)")
    if (releaseKeyAlias == null) add("keyAlias (HASHES_KEY_ALIAS)")
    if (releaseKeyPassword == null) add("keyPassword (HASHES_KEY_PASSWORD)")
    if (isEmpty() && releaseStoreFile != null && !rootProject.file(releaseStoreFile).isFile) {
        add("the keystore file at $releaseStoreFile")
    }
}
val hasReleaseKeystore = missingReleaseSigning.isEmpty()

fun taskNeedsReleaseSignature(request: String): Boolean {
    val task = request.substringAfterLast(':').lowercase()
    val codeCheck = task.startsWith("lint") || task.startsWith("test") ||
        task.startsWith("connected") || task.startsWith("dependencies") ||
        task.startsWith("dependency") || task == "tasks" || task == "help"
    if (codeCheck) return false
    return task.contains("release") || task in setOf(
        "assemble", "bundle", "build", "install", "publish", "package", "sign"
    )
}

val unsignedReleaseRequested =
    providers.gradleProperty("hashes.unsignedRelease").orNull?.equals("true", ignoreCase = true) == true

if (!hasReleaseKeystore && !unsignedReleaseRequested &&
    gradle.startParameter.taskNames.any { taskNeedsReleaseSignature(it) }
) {
    throw GradleException(
        "Release signing is not configured (missing: ${missingReleaseSigning.joinToString(", ")}). " +
            "Put storeFile, storePassword, keyAlias and keyPassword in " +
            "keystore/keystore.properties, or set HASHES_KEYSTORE_FILE, " +
            "HASHES_KEYSTORE_PASSWORD, HASHES_KEY_ALIAS and HASHES_KEY_PASSWORD. " +
            "A release build is not produced unsigned, and never with the debug key; " +
            "a builder that signs the artifact with its own key passes " +
            "-Phashes.unsignedRelease=true."
    )
}

android {
    namespace = "com.corejbh.hashes"
    compileSdk = 36

    // Pinned so a reproducible build gets the same toolchain: the NDK revision
    // and CMake version are part of the build, not whatever is installed.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.corejbh.hashes"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Single ABI. The app ships the SHA-256 engine as native code and every
        // Android phone this is for is arm64; a 32-bit build would need a
        // separate, untested lowering of the same atomics and threads.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            version = "3.22.1"
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // Dependencies metadata is stripped from the artifacts: it would otherwise be
    // stamped with the build machine's paths and identity, which is exactly what
    // makes an F-Droid reproducible build differ.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // minSdk 26 verifies APK Signature Scheme v2; an extra JAR
                // signature would add a second, weaker digest over the same files.
                enableV1Signing = false
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES"
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
