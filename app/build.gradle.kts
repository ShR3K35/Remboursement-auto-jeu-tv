plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.remboursementjeux.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.remboursementjeux.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // APK de release signé avec la clé de debug pour l'installation personnelle.
            // Remplacez par votre propre keystore pour une distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Deux versions installables côte à côte :
    //  - sms     : lit les SMS envoyés (READ_SMS) + factures. À installer par ADB
    //              (Play Protect bloque les APK téléchargés qui lisent les SMS).
    //  - facture : sans permission SMS, détection depuis la facture détaillée Prixtel.
    flavorDimensions += "detection"
    productFlavors {
        create("sms") {
            dimension = "detection"
            buildConfigField("boolean", "SMS_DETECTION", "true")
            resValue("string", "app_name", "Remboursement Jeux TV")
        }
        create("facture") {
            dimension = "detection"
            applicationIdSuffix = ".facture"
            versionNameSuffix = "-facture"
            buildConfigField("boolean", "SMS_DETECTION", "false")
            resValue("string", "app_name", "Remboursement Jeux TV (facture)")
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Extraction du texte des factures PDF (détection des SMS surtaxés facturés).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // PaymentSheet Stripe : saisie de carte conforme PCI-DSS + 3-D Secure.
    implementation("com.stripe:stripe-android:21.3.0")
}
