plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.navigation.safeargs.kotlin)
    // Room's annotation processor. Was kapt, which reads Kotlin metadata only up to 2.0.0 and so
    // cannot process dependencies built with Kotlin 2.1 (AppFunctions is). KSP also drops the
    // "Kapt currently doesn't support language version 2.0+, falling back to 1.9" fallback.
    alias(libs.plugins.ksp)
}

/**
 * Gemini support via androidx.appfunctions, off unless -Pscribcal.appfunctions=true.
 *
 * The API is alpha, needs Android 16, and is in a private preview where Gemini cannot yet invoke
 * third-party functions, so a default build must not drag in the alpha artifacts. Gating it as a
 * conditionally-declared product flavor means app/src/appfunctions/ is picked up automatically
 * when enabled and does not exist at all when disabled. With exactly one flavor declared,
 * `assembleDebug` still works as the aggregate task.
 *
 * Pinned to alpha08: alpha09 and later require compileSdk 37 and AGP 9.1, which this project is
 * not on. The library declares its own service, so no manifest entry is needed here.
 */
val appFunctionsEnabled =
    providers.gradleProperty("scribcal.appfunctions").orNull?.toBoolean() ?: false

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.tjcelaya.scribcal"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tjcelaya.scribcal"
        minSdk = 34
        targetSdk = 36
        versionCode = 5
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }

    if (appFunctionsEnabled) {
        flavorDimensions += "assistant"
        productFlavors {
            create("appfunctions") { dimension = "assistant" }
        }
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources to resolve strings/layouts.
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            pickFirsts.add("/META-INF/DEPENDENCIES")
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // RecyclerView (explicit: provides ConcatAdapter, used on the tracking screen)
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Room database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Lifecycle components
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    // Fragment and Activity KTX
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.ktx)

    // Google Play Services for authentication (downgraded to test OAuth issues)
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    // Google Drive API with conflict resolution
    implementation("com.google.apis:google-api-services-drive:v3-rev136-1.25.0") {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    
    // Google Calendar API
    implementation("com.google.apis:google-api-services-calendar:v3-rev20220715-2.0.0") {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    
    implementation("com.google.api-client:google-api-client-android:1.23.0") {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    implementation("com.google.http-client:google-http-client-gson:1.23.0") {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }

    // Google Photos API (temporarily commented out to test OAuth issues)
    // implementation("com.google.photos.library:google-photos-library-client:1.7.3") {
    //     exclude(group = "com.google.guava", module = "listenablefuture")
    // }

    // Explicitly include Guava to resolve conflicts
    implementation("com.google.guava:guava:32.1.3-android")

    testImplementation(libs.junit)
    // LocalizationTest (and the voice/ledger tests) run under Robolectric against real resources.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    if (appFunctionsEnabled) {
        add("appfunctionsImplementation", libs.appfunctions)
        // Not a transitive dependency of `appfunctions` despite appearing in its pom - the
        // @AppFunction annotation lives here, so it has to be requested explicitly.
        add("appfunctionsImplementation", libs.appfunctions.service)
        add("kspAppfunctions", libs.appfunctions.compiler)
    }
}
