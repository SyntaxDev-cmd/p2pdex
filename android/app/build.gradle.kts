plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun prop(name: String, def: String): String =
    (System.getenv(name)?.takeIf { it.isNotBlank() } ?: project.findProperty(name)?.toString() ?: def).trim()

val keystorePath = prop("KEYSTORE_FILE", "")
val hasKeystore = keystorePath.isNotEmpty() && rootProject.file(keystorePath).exists()

android {
    namespace = "com.novatv.app"
    compileSdk = 36

    defaultConfig {
        applicationId = prop("APP_ID", "com.novatv.app")
        minSdk = 21
        targetSdk = 36
        versionCode = prop("VERSION_CODE", "1").toInt()
        versionName = prop("VERSION_NAME", "1.0.0")

        buildConfigField("String", "PANEL_URL", "\"${prop("PANEL_URL", "").trimEnd('/')}\"")
        buildConfigField("String", "PANEL_KEY", "\"${prop("PANEL_KEY", "")}\"")
        resValue("string", "app_name", prop("APP_LABEL", "NovaTV"))
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystorePath)
                storePassword = prop("KEYSTORE_PASSWORD", "")
                keyAlias = prop("KEY_ALIAS", "")
                keyPassword = prop("KEY_PASSWORD", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // sem encolher/renomear recursos: as imagens ficam com o nome original
            // (res/drawable-nodpi/app_logo.png...) e aparecem no APK Editor para troca
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // sem keystore própria assina com a chave de debug (instala, mas troque por uma fixa antes de distribuir)
            signingConfig = signingConfigs.getByName(if (hasKeystore) "release" else "debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // full: APK para instalar direto (TV Box, celular), com atualização automática pelo painel
    // play: versão para a Google Play (sem instalador próprio; atualiza pela loja). Gere com bundlePlayRelease
    flavorDimensions += "store"
    productFlavors {
        create("full") {
            dimension = "store"
            isDefault = true
            buildConfigField("boolean", "PLAY_STORE", "false")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "PLAY_STORE", "true")
        }
    }

    splits {
        abi {
            isEnable = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
