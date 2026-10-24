import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
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
val noDeviceLicenseRequested = project.hasProperty("noDeviceLicense")
val editionVersionSuffix = if (noDeviceLicenseRequested) "-no-license" else ""
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

/** The patcher host for the embedded JVM, see [embeddedJvmAssets]. */
val patcherHost: Configuration = configurations.create("patcherHost") {
    isCanBeConsumed = false
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
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)

    // Haze (blur of what scrolls under floating dialog chrome)
    implementation(libs.haze)
    implementation(libs.haze.blur)

    // Coil (async image loading, network image)
    implementation(libs.coil.compose)
    implementation(libs.coil.appiconloader)
    implementation(libs.appiconloader.iconloaderlib)

    // KotlinX
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collection.immutable)
    implementation(libs.kotlinx.datetime)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Morphe
    implementation(libs.apksig)
    implementation(project(":patcher-core"))
    implementation(libs.arsclib)
    implementation(libs.morphe.patcher)
    patcherHost(project(":patcher-core", "hostJar"))
    // Unpacks the embedded JVM, which ships compressed with xz
    implementation(libs.xz)

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

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.workmanager)

    // Licenses
    implementation(libs.about.libraries.core)
    implementation(libs.about.libraries.m3)

    // Ktor
    implementation(libs.ktor.core)
    implementation(libs.ktor.okhttp)
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.serialization)

    // Google Play Services availability is used only for device diagnostics.
    implementation(libs.play.services.base)

    // Reorderable lists
    implementation(libs.reorderable)

    // Icons
    implementation(libs.icons.material.symbols.rounded)
    implementation(libs.icons.material.symbols.rounded.filled)
    implementation(libs.icons.font.awesome.brands)

    // Semantic versioning parser
    implementation(libs.semver.parser)

    // Unit tests
    testImplementation(libs.kotlin.test.junit)
}

/**
 * Languages Morphe is translated into as language and region pairs, read from the resource folders
 * Crowdin writes, so a new language needs no change anywhere else.
 */
val translations = project.file("src/main/res").listFiles().orEmpty()
    .mapNotNull { Regex("values-([a-z]{2,3})(?:-r([A-Z]{2}))?").matchEntire(it.name) }
    .map { it.groupValues[1] to it.groupValues[2] }
    .sortedWith(compareBy({ it.first }, { it.second }))

/**
 * Locales kept from library resources. Each comes with and without the region, since libraries
 * mostly use the bare one.
 */
val translatedLocales = translations
    .flatMap { (language, region) ->
        // Filipino is still filed under its legacy Tagalog code by some libraries
        listOfNotNull(
            language,
            "$language-r$region".takeIf { region.isNotEmpty() },
            "tl".takeIf { language == "fil" }
        )
    }
    .plus("en")
    .toSet()

/**
 * Translations as BCP 47 tags for the in-app language picker, written as a Java array literal for
 * BuildConfig. Resource folders still name Indonesian, Hebrew and Yiddish by their withdrawn ISO
 * codes, which tags no longer accept.
 */
val translationTags = translations.joinToString(prefix = "{", postfix = "}") { (language, region) ->
    val tagLanguage = mapOf("in" to "id", "iw" to "he", "ji" to "yi")[language] ?: language
    val tag = listOf(tagLanguage, region).filter { it.isNotEmpty() }.joinToString("-")
    "\"$tag\""
}

/**
 * The JVM the app embeds for patching, an Android build of OpenJDK per ABI it ships one for. A 32 bit
 * x86 device patches inside the app instead.
 *
 * 17, whose ports have run on Android devices the longest, the 32-bit ARM one above all, and which
 * the patcher and the patches are built for.
 */
val embeddedJvmFeature = 17
val embeddedJvmRelease = "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre$embeddedJvmFeature"
val embeddedJvms = mapOf(
    "arm64-v8a" to Triple("jre17-android-arm64.tar.xz", "e162c860fe05ee4a4e4af7606437419879f6c748386a7b09fa77d10db6a64091", "linux-aarch64"),
    "armeabi-v7a" to Triple("jre17-android-arm.tar.xz", "4a9134f1ebf6340dd855805d351712462cddab6f3c2684da7e7da10ccf06648d", "linux-arm"),
    "x86_64" to Triple("jre17-android-x86_64.tar.xz", "893e27d2aed8b40407f29fe939e2a0f193e5d55f72892a303db634b0808a2b61", "linux-amd64")
)

/**
 * The modules patching can reach: what the patcher and its libraries use, plus what ART offers patches
 * beyond that, so a patch that runs inside the app also runs in the JVM.
 */
val embeddedJvmModules = listOf(
    "java.base", "java.logging", "java.xml", "java.sql", "jdk.unsupported", "jdk.charsets", "jdk.crypto.ec"
)

/**
 * What of the build the linked modules need, its native libraries above all. A build ships the
 * server VM, but for the 32-bit ARM one, which ships the client VM.
 */
val embeddedJvmFiles = listOf(
    "lib/*/libjvm.so",
    "lib/libjava.so", "lib/libjimage.so", "lib/libzip.so", "lib/libnet.so", "lib/libnio.so",
    "lib/libverify.so",
    "lib/jvm.cfg", "lib/tzdb.dat", "lib/security", "conf"
)

val embeddedJvmTasks = embeddedJvms.map { (abi, build) ->
    val (file, sha256, platform) = build
    tasks.register<PrepareEmbeddedJvmTask>("prepareEmbeddedJvm${abi.replace("-", "").replace("_", "").replaceFirstChar(Char::uppercase)}") {
        group = "build"
        description = "Links the embedded JVM for $abi."
        url.set("$embeddedJvmRelease/$file")
        this.sha256.set(sha256)
        targetPlatform.set(platform)
        modules.set(embeddedJvmModules)
        runtimeFiles.set(embeddedJvmFiles)
        jdk.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(embeddedJvmFeature)) })
        downloadCache.set(File(gradle.gradleUserHomeDir, "caches/morphe-embedded-jvm"))
        outputArchive.set(layout.buildDirectory.file("intermediates/embeddedJvm/$abi.zip.xz"))
        outputImage.set(layout.buildDirectory.file("intermediates/embeddedJvm/$abi.modules"))
    }
}

/** The ABIs of [embeddedJvms] as a Java array literal for BuildConfig. */
val embeddedJvmAbis = embeddedJvms.keys.joinToString(prefix = "{", postfix = "}") { abi -> "\"$abi\"" }

/** Changes with whatever goes into the runtime, which the app then unpacks again. */
val embeddedJvmId = (embeddedJvms.values.map { build -> build.second } + embeddedJvmModules + embeddedJvmFiles)
    .hashCode().toUInt().toString(16)

/** Assets the embedded JVM runs from, read by JvmInstallation. */
val embeddedJvmAssets = tasks.register<EmbeddedJvmAssetsTask>("embeddedJvmAssets") {
    group = "build"
    description = "Lays out the embedded JVM runtimes and the patcher host as assets."
    runtimes.from(embeddedJvmTasks.map { task -> task.flatMap { it.outputArchive } })
    images.from(embeddedJvmTasks.map { task -> task.flatMap { it.outputImage } })
    baseAbi.set(embeddedJvms.keys.first())
    hostJar.from(patcherHost)
    outputDirectory.set(layout.buildDirectory.dir("generated/embeddedJvm"))
}

android {
    namespace = "app.morphe.manager"
    compileSdk = 37

    defaultConfig {
        applicationId = if (noDeviceLicenseRequested) {
            "${safeProfileValue("managerPackage")}.nolicense"
        } else {
            safeProfileValue("managerPackage")
        }
        minSdk = 26

        versionName = version.toString() + editionVersionSuffix

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
        buildConfigField("String", "LICENSE_PURCHASE_URL", "\"https://yingshi-license.pppp77088.workers.dev/buy\"")
        buildConfigField("String", "LICENSE_PRODUCT_ID", "\"symorphe\"")
        buildConfigField("boolean", "NO_LICENSE_EDITION", noDeviceLicenseRequested.toString())
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

        buildConfigField("String[]", "TRANSLATIONS", translationTags)

        buildConfigField("String[]", "EMBEDDED_JVM_ABIS", embeddedJvmAbis)
        buildConfigField("String", "EMBEDDED_JVM_ID", "\"$embeddedJvmId\"")

        // The embedded JVM links against the shared C++ runtime, which the app ships alongside the launcher
        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }

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
            buildConfigField(
                "boolean",
                "DEVICE_LICENSE_REQUIRED",
                (!signAsDebugRequested && !noDeviceLicenseRequested).toString(),
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Unit tests run on the JVM, where android.util.Log is only a stub
    testOptions.unitTests.isReturnDefaultValues = true

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
                "/org/bouncycastle/x509/**.properties",

                // ANTLR tool templates and grammar tokens; smali only needs the runtime at patch time
                "/org/antlr/codegen/**",
                "/org/antlr/tool/**",
                "/com/android/tools/smali/smali/*.tokens",

                // Mocking agent resources whose classes R8 already strips
                "/org/mockito/**",
                "/win32-x86/**",
                "/win32-x86-64/**"
            )
        )

        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        // The embedded JVM comes packed already, so deflating it again only slows down unpacking it
        noCompress += "xz"

        // Libraries ship strings in far more languages than Morphe has, which only bloat resources.arsc
        @Suppress("UnstableApiUsage")
        localeFilters += translatedLocales

        // Lists the translations for the per-app language setting of Android 13+ from the same
        // resource folders, with the default locale taken from res/resources.properties
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
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
        lintConfig = file("lint.xml")
        disable += setOf("MissingTranslation")
        // Morphe ships as an APK, never as a bundle split by language
        disable += setOf("AppBundleLocaleChanges")
        // Crowdin drops a removed string from the translations only on its next sync
        warning += setOf("ExtraTranslation")
        baseline = file("lint-baseline.xml")
    }
}

// APK output file name
base.archivesName.set(provider {
    "${rootProject.name}-$version$editionVersionSuffix"
})

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
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

    // CI logs are all anyone reads of a failed run, so a failure prints its whole message there
    withType<Test>().configureEach {
        testLogging {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

/**
 * Launcher icons on offer, the one place they are defined. Each is a background with a mark over it.
 * The launcher only shows icons the manifest declares, so the build generates from this an
 * activity-alias per icon, the adaptive icon it shows and the catalog the app picks from.
 *
 * Backgrounds are listed in picker order. A background's drawable is ic_launcher_background_<id>
 * and its name settings_appearance_app_icon_<id>; a mark's name is
 * settings_appearance_app_icon_mark_<id>.
 *
 * The launcher remembers an icon by its alias, MainActivity_<Background>, plus _<Mark> for any mark
 * but the background's base one. Released ids and base marks must therefore never change, or users
 * of that icon would lose it on update. A background's color can change freely.
 */
val launcherIconMarks = linkedMapOf(
    "color" to "ic_symorphe_foreground",
    "white" to "ic_symorphe_foreground",
    "black" to "ic_symorphe_foreground"
)

val launcherIconBackgrounds = listOf(
    // id to (base mark, other marks that read on it). A mark reads when it keeps a contrast of
    // about 2.5:1 against the background, the brand mark about 2:1 at its weaker end
    "default" to ("color" to listOf("black")),
    "light_2" to ("color" to listOf("black")),
    "light_3" to ("color" to listOf("black")),
    "light_4" to ("color" to listOf("black")),
    "light_5" to ("color" to listOf("black")),
    "light_6" to ("color" to listOf("black")),
    "light_7" to ("color" to listOf("black")),
    "dark_1" to ("white" to listOf("black")),
    "accent_1" to ("white" to listOf("black")),
    "accent_4" to ("white" to listOf("black")),
    "dark_3" to ("white" to listOf("black")),
    "dark_4" to ("white" to emptyList()),
    "dark_5" to ("white" to listOf("black")),
    "dark_2" to ("color" to listOf("white")),
    "accent_2" to ("white" to listOf("color")),
    "accent_3" to ("black" to listOf("color"))
)

val generateLauncherIcons = tasks.register<GenerateLauncherIconsTask>("generateLauncherIcons") {
    group = "build"
    description = "Generates the launcher icon aliases, adaptive icons and app catalog."
    marks.set(launcherIconMarks)
    backgrounds.set(launcherIconBackgrounds.map { (id, marks) -> listOf(id, marks.first) + marks.second })
    resDirectory.set(layout.buildDirectory.dir("generated/launcherIcons/res"))
    kotlinDirectory.set(layout.buildDirectory.dir("generated/launcherIcons/kotlin"))
    manifestFile.set(layout.buildDirectory.file("generated/launcherIcons/AndroidManifest.xml"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateLauncherIcons, GenerateLauncherIconsTask::resDirectory)
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateLauncherIcons, GenerateLauncherIconsTask::kotlinDirectory)
        variant.sources.manifests.addGeneratedManifestFile(generateLauncherIcons, GenerateLauncherIconsTask::manifestFile)
        variant.sources.assets?.addGeneratedSourceDirectory(embeddedJvmAssets, EmbeddedJvmAssetsTask::outputDirectory)
    }
}
