import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

// AWS S3 settings come from aws.properties (git-ignored) so the keys never end up on GitHub.
val awsProps = Properties().apply {
    val f = rootProject.file("aws.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun awsProp(name: String): String = "\"" + (awsProps.getProperty(name) ?: "").trim() + "\""

android {
    namespace = "com.example.groceryapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.groceryapp"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "AWS_ACCESS_KEY", awsProp("aws.accessKey"))
        buildConfigField("String", "AWS_SECRET_KEY", awsProp("aws.secretKey"))
        buildConfigField("String", "AWS_BUCKET", awsProp("aws.bucket"))
        buildConfigField("String", "AWS_REGION", awsProp("aws.region"))
    }

    buildTypes {
        release {
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.amazonaws:aws-android-sdk-s3:2.77.0") // AWS S3 (profile photos)

}