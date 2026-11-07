import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.sentry.android.gradle") apply false
}

// Declaring the plugin does not apply it. A local build never registers upload tasks.
val sentryCiUpload = providers.gradleProperty("sentryCiUpload").map(String::toBooleanStrict).orElse(false).get()
if (sentryCiUpload) {
    if (providers.environmentVariable("GITHUB_ACTIONS").orNull != "true") {
        throw GradleException("sentryCiUpload is only supported in GitHub Actions; local builds never upload mappings.")
    }
    for (name in listOf("SENTRY_AUTH_TOKEN", "SENTRY_ORG", "SENTRY_PROJECT")) {
        if (providers.environmentVariable(name).orNull.isNullOrBlank()) {
            throw GradleException("$name is required for CI mapping upload.")
        }
    }
    apply(plugin = "io.sentry.android.gradle")
    extensions.configure<io.sentry.android.gradle.extensions.SentryPluginExtension> {
        authToken.set(providers.environmentVariable("SENTRY_AUTH_TOKEN"))
        org.set(providers.environmentVariable("SENTRY_ORG"))
        projectName.set(providers.environmentVariable("SENTRY_PROJECT"))
        ignoredBuildTypes.set(setOf("debug"))
        includeProguardMapping.set(true)
        autoUploadProguardMapping.set(true)
        autoInstallation.enabled.set(false)
        tracingInstrumentation.enabled.set(false)
        includeSourceContext.set(false)
        autoUploadSourceContext.set(false)
        uploadNativeSymbols.set(false)
        autoUploadNativeSymbols.set(false)
        includeNativeSources.set(false)
        includeDependenciesReport.set(false)
        telemetry.set(false)
        sizeAnalysis.enabled.set(false)
        distribution.enabled.set(false)
        snapshots.enabled.set(false)
    }
}

tasks.register("verifySentryBuild") {
    group = "verification"
    description = "Checks the CI-only mapping upload boundary without executing uploads."
    doLast {
        check(plugins.hasPlugin("io.sentry.android.gradle") == sentryCiUpload)
        val uploads = tasks.names.filter { it.startsWith("uploadSentry") }
        if (!sentryCiUpload) {
            check(uploads.isEmpty()) { "Local builds must not register Sentry upload tasks." }
            logger.lifecycle("Sentry verification: local mode, no plugin or upload tasks.")
        } else {
            val options = project.extensions.getByType<io.sentry.android.gradle.extensions.SentryPluginExtension>()
            check(uploads == listOf("uploadSentryProguardMappingsRelease")) { "Only Release mappings may be uploaded: $uploads" }
            check(options.includeProguardMapping.get() && options.autoUploadProguardMapping.get())
            check(!options.autoInstallation.enabled.get() && !options.tracingInstrumentation.enabled.get())
            check(!options.includeSourceContext.get() && !options.autoUploadSourceContext.get())
            check(!options.uploadNativeSymbols.get() && !options.autoUploadNativeSymbols.get())
            check(!options.includeNativeSources.get() && !options.includeDependenciesReport.get())
            check(!options.telemetry.get() && !options.sizeAnalysis.enabled.get())
            check(!options.distribution.enabled.get() && !options.snapshots.enabled.get())
            logger.lifecycle("Sentry verification: CI mapping only; runtime SDK and tracing unchanged.")
        }
    }
}

val signingProperties = Properties().apply {
    val path = providers.gradleProperty("signingPropertiesFile").orElse("signing.properties").get()
    val configuration = rootProject.file(path)
    if (configuration.isFile) {
        configuration.inputStream().use { load(it) }
    } else if (providers.gradleProperty("signingPropertiesFile").isPresent) {
        throw GradleException("The specified signingPropertiesFile does not exist.")
    }
}

fun signingOptions(variant: String): Map<String, String> = mapOf(
    "storeFile" to "KEYSTORE_PATH",
    "storePassword" to "STORE_PASSWORD",
    "keyAlias" to "KEY_ALIAS",
    "keyPassword" to "KEY_PASSWORD",
    "storeType" to "KEYSTORE_TYPE",
).mapNotNull { (property, environment) ->
    val value = providers.environmentVariable("${variant.uppercase()}_$environment").orNull
        ?: signingProperties.getProperty("$variant.$property")
    value?.takeIf { it.isNotBlank() }?.let { property to it }
}.toMap()

val requiredSigningOptions = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val debugSigning = signingOptions("debug")
val releaseSigning = signingOptions("release")

fun validateSigning(variant: String, options: Map<String, String>) {
    val missing = requiredSigningOptions.filter { it !in options }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "$variant signing is incomplete: ${missing.joinToString()}. " +
                "Configure signing.properties or the ${variant.uppercase()}_* environment variables.",
        )
    }
    if (!rootProject.file(options.getValue("storeFile")).isFile) {
        throw GradleException("$variant keystore does not exist. Check the signing storeFile configuration.")
    }
}

android {
    namespace = "com.kudo.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.kudo.android"
        manifestPlaceholders["appLabel"] = "@string/app_name"
        manifestPlaceholders["linkHost"] = "linux.do"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("versionCode").orElse("1").get()
            .toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
            ?: throw GradleException("versionCode must be an integer between 1 and 2100000000.")
        versionName = providers.gradleProperty("versionName").orElse("0.1.0").get()
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (debugSigning.isNotEmpty()) {
            validateSigning("Debug", debugSigning)
            getByName("debug") {
                storeFile = rootProject.file(debugSigning.getValue("storeFile"))
                storePassword = debugSigning.getValue("storePassword")
                keyAlias = debugSigning.getValue("keyAlias")
                keyPassword = debugSigning.getValue("keyPassword")
                storeType = debugSigning["storeType"] ?: "PKCS12"
            }
        }
        create("release") {
            storeFile = releaseSigning["storeFile"]?.let { rootProject.file(it) }
            storePassword = releaseSigning["storePassword"]
            keyAlias = releaseSigning["keyAlias"]
            keyPassword = releaseSigning["keyPassword"]
            storeType = releaseSigning["storeType"] ?: "PKCS12"
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "酷DO 开发版"
            manifestPlaceholders["linkHost"] = "debug.kudo.invalid"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/dohJni"))
    if (providers.gradleProperty("dohProbe").orNull == "true") {
        sourceSets.getByName("androidTest").jniLibs.srcDir(layout.buildDirectory.dir("generated/dohProbeJni"))
    }
}

val buildDoh by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust arm64 DoH/ECH transport with 16KB alignment."
    inputs.files(rootProject.fileTree("native/doh") { include("Cargo.toml", "Cargo.lock", "src/**") })
    inputs.file(rootProject.file("scripts/build_doh.py"))
    inputs.file(rootProject.file("scripts/native_notices.py"))
    inputs.file(rootProject.file("app/src/main/assets/licenses/native-doh-NOTICES.txt"))
    inputs.files(rootProject.fileTree("native/doh/licenses"))
    outputs.file(layout.buildDirectory.file("generated/dohJni/arm64-v8a/libkudo_doh.so"))
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3",
        rootProject.file("scripts/build_doh.py"), "--sdk", android.sdkDirectory.absolutePath)
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildDoh) }

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Rejects Release builds without a complete, private signing configuration."
    doLast { validateSigning("Release", releaseSigning) }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("io.github.kyant0:backdrop:1.0.6")
    implementation("io.github.kyant0:shapes:1.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("io.coil-kt.coil3:coil-gif:3.3.0")
    implementation("io.coil-kt.coil3:coil-svg:3.3.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.github.penfeizhou.android.animation:apng:3.0.5")
    implementation("com.github.penfeizhou.android.animation:awebp:3.0.5")
    implementation("com.github.penfeizhou.android.animation:avif:3.0.5")
    implementation("org.aomedia.avif.android:avif:1.1.1.14d8e3c4")
    implementation("androidx.vectordrawable:vectordrawable-animated:1.2.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.24.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.sentry:sentry-android-core:8.63.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
