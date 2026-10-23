plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

android {
    namespace = "com.wgtunnelx.xray"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release { isMinifyEnabled = false }
        create(Constants.NIGHTLY) { initWith(getByName(Constants.RELEASE)) }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Xray-core gomobile binding (2dust/AndroidLibXrayLite), fetched by XrayLib.ensure()
    api(files(XrayLib.ensure(project)))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)
    implementation(libs.timber)

    testImplementation(libs.junit)
}
