plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
    alias(libs.plugins.navigation.safeargs.kotlin)
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

    // Room database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
