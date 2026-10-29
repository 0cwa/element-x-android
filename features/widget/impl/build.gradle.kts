import extension.setupDependencyInjection
import extension.testCommonDependencies

plugins {
    id("io.element.android-compose-library")
    id("kotlin-parcelize")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.element.android.features.widget.impl"

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

setupDependencyInjection()

dependencies {
    implementation(projects.appconfig)
    implementation(projects.features.enterprise.api)
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.androidutils)
    implementation(projects.libraries.core)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.featureflag.api)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.network)
    implementation(projects.libraries.preferences.api)
    implementation(projects.libraries.sessionStorage.api)
    implementation(projects.libraries.uiStrings)
    api(projects.libraries.widget)
    implementation(projects.services.appnavstate.api)
    implementation(projects.services.toolbox.api)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.serialization.json)
    api(projects.features.widget.api)

    testCommonDependencies(libs, true)
    testImplementation(projects.features.widget.test)
    testImplementation(projects.libraries.preferences.test)
    testImplementation(projects.libraries.sessionStorage.test)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.services.appnavstate.impl)
    testImplementation(projects.services.appnavstate.test)
    testImplementation(projects.services.toolbox.test)
}
