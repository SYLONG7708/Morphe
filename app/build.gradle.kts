import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.random.Random

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.devtools)
    alias(libs.plugins.about.libraries)
    alias(libs.plugins.about.libraries.android)
    signing
}

val safeProfile = Properties().apply {
    rootProject.file("config/uis7870-safe.properties").inputStream().use(::load)
}

fun safeProfileValue(key: String): String =
    requireNotNull(safeProfile.getProperty(key)?.trim()?.takeIf(String::isNotEmpty)) {
        "Missing $key in config/uis7870-safe.properties"
    }

val configuredVendorGroup = safeProfileValue("vendorGroup")
val safeVendorGroup = providers.gradleProperty("safeVendorGroup").getOrElse(configuredVendorGroup)
require(safeVendorGroup.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"))) {
    "Invalid safeVendorGroup: $safeVendorGroup"
}
val safePatchedYouTubePackage = if (safeVendorGroup == configuredVendorGroup) {
    safeProfileValue("patchedYouTubePackage")
} else {
    "$safeVendorGroup.android.youtube"
}
val safeMicrogPackage = if (safeVendorGroup == configuredVendorGroup) {
    safeProfileValue("microgPackage")
} else {
    "$safeVendorGroup.android.gms"
}

val releaseRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}
val signAsDebugRequested = project.hasProperty("signAsDebug")
val releaseKeystoreFile = providers.environmentVariable("SYMORPHE_KEYSTORE_FILE")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let(::file)
    ?: file("keystore.jks")
val expectedSigningCertificateSha256 = providers
    .gradleProperty("expectedSigningCertSha256")
    .orElse(providers.environmentVariable("SYMORPHE_EXPECTED_SIGNING_CERT_SHA256"))
    .getOrElse("")
    .replace(":", "")
    .uppercase()
val releaseSigningCredentialsAvailable = listOf(
    System.getenv("KEYSTORE_PASSWORD"),
    System.getenv("KEYSTORE_ENTRY_ALIAS"),
    System.getenv("KEYSTORE_ENTRY_PASSWORD"),
).all { !it.isNullOrBlank() }

if (releaseRequested && !signAsDebugRequested) {
    require(releaseKeystoreFile.isFile) {
        "SyMorphe production release requires app/keystore.jks; test builds must opt in with -PsignAsDebug"
    }
    require(releaseSigningCredentialsAvailable) {
        "SyMorphe production release signing credentials are incomplete"
    }
    require(expectedSigningCertificateSha256.matches(Regex("[0-9A-F]{64}"))) {
        "SyMorphe production release requires SYMORPHE_EXPECTED_SIGNING_CERT_SHA256"
    }
}

val bundledEcosystemDir = providers.gradleProperty("bundledEcosystemDir")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.let(rootProject::file)
val bundledPatchAsset = bundledEcosystemDir?.resolve("autopatch/patches.mpp")
val bundledMicrogAsset = bundledEcosystemDir?.resolve("autopatch/microg.apk")
val bundledEcosystemEnabled = bundledEcosystemDir != null

if (bundledEcosystemEnabled) {
    require(bundledEcosystemDir!!.isDirectory) {
        "bundledEcosystemDir is not a directory: $bundledEcosystemDir"
    }
    require(bundledPatchAsset!!.isFile) {
        "Missing bundled patch asset: $bundledPatchAsset"
    }
    require(bundledMicrogAsset!!.isFile) {
        "Missing bundled MicroG asset: $bundledMicrogAsset"
    }
}

fun sha256(file: File?): String {
    if (file == null) return ""
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun manifestAttribute(file: File?, attribute: String): String {
    if (file == null) return ""
    return ZipFile(file).use { zip ->
        val entry = requireNotNull(zip.getEntry("META-INF/MANIFEST.MF")) {
            "Missing META-INF/MANIFEST.MF in $file"
        }
        val unfolded = mutableListOf<String>()
        zip.getInputStream(entry).bufferedReader().useLines { lines ->
            lines.takeWhile { it.isNotEmpty() }.forEach { line ->
                if (line.startsWith(" ") && unfolded.isNotEmpty()) {
                    val last = unfolded.lastIndex
                    unfolded[last] = unfolded[last] + line.drop(1)
                } else {
                    unfolded += line
                }
            }
        }
        val prefix = "$attribute:"
        requireNotNull(
            unfolded.firstOrNull { it.startsWith(prefix, ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        ) {
            "Missing $attribute in $file"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.ktx)
    implementation(libs.runtime.ktx)
    implementation(libs.runtime.compose)
    implementation(libs.splash.screen)
    implementation(libs.activity.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.preferences.datastore)
    implementation(libs.appcompat)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.preview)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.livedata)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)

    // Accompanist
    implementation(libs.accompanist.drawablepainter)

    // Placeholder
    implementation(libs.placeholder.material3)

    // Coil (async image loading, network image)
    implementation(libs.coil.compose)
    implementation(libs.coil.appiconloader)

    // KotlinX
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collection.immutable)
    implementation(libs.kotlinx.datetime)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    annotationProcessor(libs.room.compiler)
    ksp(libs.room.compiler)

    // Morphe
    implementation(libs.apksig)
    implementation(libs.arsclib)
    implementation(libs.morphe.patcher)
    implementation(libs.morphe.library)

    implementation(libs.androidx.documentfile)

    // Native processes
    implementation(libs.kotlin.process)

    // HiddenAPI
    compileOnly(libs.hidden.api.stub)
    implementation(libs.hidden.api.bypass)

    // Shizuku / Sui
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // LibSU
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.nio)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.navigation)
    implementation(libs.koin.workmanager)

    // Licenses
    implementation(libs.about.libraries.core)
    implementation(libs.about.libraries.m3)

    // Ktor
    implementation(libs.ktor.core)
    implementation(libs.ktor.logging)
    implementation(libs.ktor.okhttp)
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.serialization)

    // Google Play Services availability is used only for device diagnostics.
    implementation(libs.play.services.base)

    // Markdown
    implementation(libs.markdown.renderer)

    // Fading Edges
    implementation(libs.fading.edges)

    // Scrollbars
    implementation(libs.scrollbars)

    // EnumUtil
    implementation(libs.enumutil)
    ksp(libs.enumutil.ksp)

    // Reorderable lists
    implementation(libs.reorderable)

    // Compose Icons
    implementation(libs.compose.icons.fontawesome)

    // Semantic versioning parser
    implementation(libs.semver.parser)

    testImplementation(kotlin("test-junit"))
}

android {
    namespace = "app.morphe.manager"
    compileSdk = 37

    defaultConfig {
        applicationId = safeProfileValue("managerPackage")
        minSdk = 26

        versionName = version.toString()

        // VersionCode derived from current time (1-minute intervals) + offset.
        val nowMillis = System.currentTimeMillis()
        val timestampVersionCode = (nowMillis / (60 * 1000)).toInt()
        // SyMorphe uses its own application ID and signing lineage.
        val versionCodeOffset = 0
        versionCode = timestampVersionCode + versionCodeOffset

        // Expose the resolved morphe-patcher version so PatcherViewModel can compare it
        // against the Patcher-Version declared in .mpp bundle manifests at runtime.
        buildConfigField("String", "PATCHER_VERSION", "\"${libs.versions.morphe.patcher.get()}\"")
        buildConfigField("String", "SAFE_VENDOR_GROUP", "\"$safeVendorGroup\"")
        buildConfigField(
            "String",
            "SAFE_PATCHED_YOUTUBE_PACKAGE",
            "\"$safePatchedYouTubePackage\"",
        )
        buildConfigField("String", "SAFE_MICROG_PACKAGE", "\"$safeMicrogPackage\"")
        buildConfigField(
            "String",
            "EXPECTED_SIGNING_CERT_SHA256",
            "\"$expectedSigningCertificateSha256\"",
        )
        buildConfigField("String", "LICENSE_API_BASE_URL", "\"https://yingshi-license.pppp77088.workers.dev\"")
        buildConfigField("String", "LICENSE_PRODUCT_ID", "\"symorphe\"")
        buildConfigField(
            "String",
            "LICENSE_PUBLIC_KEY_SPKI_B64",
            "\"MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA5XHmjYNztqYbJxC2gowa9Zg0ebOf/4qp+SECxMWPGOU8RhrteJtA8I1ciyiJn8gi46UF+WT2JNCLDytbstfOuPsAVQs8hHLdhNkkS5zJF7DE8L4/mJk6Q5h4AlL7XLWsNvVj3+jWGUc/q/28ZJ4G9/EwxqWtQDUMihqwYQ1YLmotG35mUChD4gzJzsno5ms9DaVyvpgP2Zl31Bom78PtHLIB4XBtqn2LPYz0jRETlTBMZpXNG8QpDzKu8GVTsd1QpDZShA+0YFD1JOvePu0oPDP4HLnElzRH1ADMjQe3bCefw0dPRzzKodkDIgZ/geCl1rZrKU2yNMdjxQaNTpZOTGLHfHqjptwEVSV9YIbIYZ8F+SdmhyR/GLNMBOiWGoI4F/vA0pj8xDJIot2a6YHrQyhXI066PjEYGHOximLkmvzJx2OaxT3cp0xUu6O7pwkQP+rbdAdXG1lTxcbE5v2sbUkvzTLx6QPDJ0uc/T2I8gIelVVuRi6LJhk02wQDIytVAgMBAAE=\"",
        )
        buildConfigField(
            "boolean",
            "ALLOW_INSECURE_TEST_SIGNING",
            signAsDebugRequested.toString(),
        )
        buildConfigField("int", "SAFE_PROFILE_REVISION", safeProfileValue("profileRevision"))
        buildConfigField("boolean", "BUNDLED_ECOSYSTEM_ENABLED", bundledEcosystemEnabled.toString())
        buildConfigField("String", "BUNDLED_PATCH_ASSET", "\"autopatch/patches.mpp\"")
        buildConfigField("String", "BUNDLED_PATCH_SHA256", "\"${sha256(bundledPatchAsset)}\"")
        buildConfigField(
            "String",
            "BUNDLED_PATCH_VERSION",
            "\"${manifestAttribute(bundledPatchAsset, "Version")}\"",
        )
        buildConfigField("String", "BUNDLED_MICROG_ASSET", "\"autopatch/microg.apk\"")
        buildConfigField("String", "BUNDLED_MICROG_SHA256", "\"${sha256(bundledMicrogAsset)}\"")
        buildConfigField(
            "String",
            "BUNDLED_YOUTUBE_SOURCE_VERSION",
            "\"${safeProfileValue("youtubeStableVersion")}\"",
        )
        buildConfigField(
            "int",
            "BUNDLED_YOUTUBE_SOURCE_VERSION_CODE",
            safeProfileValue("youtubeStableVersionCode"),
        )
        buildConfigField(
            "String",
            "BUNDLED_YOUTUBE_SOURCE_SHA256",
            "\"${safeProfileValue("youtubeStableSha256")}\"",
        )
        buildConfigField(
            "String",
            "BUNDLED_YOUTUBE_SIGNER_SHA256",
            "\"${safeProfileValue("youtubeSignerSha256")}\"",
        )

        vectorDrawables.useSupportLibrary = true
    }

    sourceSets {
        if (bundledEcosystemEnabled) {
            getByName("main").assets.directories.add(bundledEcosystemDir!!.absolutePath)
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("long", "BUILD_ID", "${Random.nextLong()}L")
            buildConfigField("boolean", "DEVICE_LICENSE_REQUIRED", "false")
        }

        release {
            if (!project.hasProperty("noProguard")) {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }

            signingConfig = if (signAsDebugRequested) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.create("release") {
                    storeFile = releaseKeystoreFile
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEYSTORE_ENTRY_ALIAS")
                    keyPassword = System.getenv("KEYSTORE_ENTRY_PASSWORD")
                }
            }

            buildConfigField("long", "BUILD_ID", "0L")
            buildConfigField("boolean", "DEVICE_LICENSE_REQUIRED", (!signAsDebugRequested).toString())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes.addAll(
            listOf(
                // Build junk
                "/prebuilt/**",
                "/smali.properties",
                "/baksmali.properties",
                "/properties/apktool.properties",

                // Kotlin / debug metadata
                "/META-INF/*.version",
                "/META-INF/*.kotlin_module",
                "/kotlin-tooling-metadata.json",
                "/DebugProbesKt.bin",

                // Specific META-INF junk
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",

                // Crypto optional metadata
                "/org/bouncycastle/pqc/**.properties",
                "/org/bouncycastle/x509/**.properties"
            )
        )

        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        disable += setOf("MissingTranslation")
        baseline = file("lint-baseline.xml")
    }
}

// APK output file name
base.archivesName.set(provider {
    "${rootProject.name}-$version"
})

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.time.ExperimentalTime"
        )
    }
}

aboutLibraries {
    collect {
        configPath = file("aboutlibraries")
    }
    library {
        duplicationMode = DuplicateMode.MERGE
        duplicationRule = DuplicateRule.EXACT
    }
}

tasks {
    whenTaskAdded {
        if (name.startsWith("lintVital")) {
            enabled = false
        }
    }
}
