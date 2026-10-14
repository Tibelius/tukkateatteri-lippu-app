import java.util.Properties

fun loadLocalProperties(fileName: String): Properties = Properties().apply {
    rootProject.file(fileName).takeIf { it.isFile }?.inputStream()?.use(::load)
}

val releaseKeystoreProperties = loadLocalProperties("keystore.properties")
val hasReleaseKeystore = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
    releaseKeystoreProperties.getProperty(it)?.isNotBlank() == true
}
val zettleProperties = loadLocalProperties("zettle.properties")
val zettleClientId = zettleProperties.getProperty("clientId").orEmpty()
val zettleRedirectScheme = zettleProperties.getProperty("redirectScheme", "tukkateatteri-zettle")
val zettleRedirectHost = zettleProperties.getProperty("redirectHost", "zettle-auth")
val zettleRedirectUrl = "$zettleRedirectScheme://$zettleRedirectHost"

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
}

base {
    archivesName.set("Tukkateatteri")
}

android {
    namespace = "fi.tukkateatteri"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "fi.tukkateatteri"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 30
        targetSdk = 37
        versionCode = 9
        versionName = "0.6.0"
        manifestPlaceholders["zettleRedirectScheme"] = zettleRedirectScheme
        manifestPlaceholders["zettleRedirectHost"] = zettleRedirectHost
        buildConfigField("String", "ZETTLE_REDIRECT_URL", zettleRedirectUrl.asBuildConfigString())
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ZETTLE_DEVELOPER_MODE", "true")
            buildConfigField(
                "String",
                "ZETTLE_CLIENT_ID",
                (zettleClientId.ifBlank { "developer-mode" }).asBuildConfigString()
            )
        }
        release {
            buildConfigField("boolean", "ZETTLE_DEVELOPER_MODE", "false")
            buildConfigField("String", "ZETTLE_CLIENT_ID", zettleClientId.asBuildConfigString())
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.google.play.services.auth)
    implementation(libs.zettle.core)
    implementation(libs.zettle.card.reader.ui)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
