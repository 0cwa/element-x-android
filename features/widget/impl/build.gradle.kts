import extension.setupDependencyInjection
import extension.testCommonDependencies

plugins {
    id("io.element.android-compose-library")
}

android {
    namespace = "io.element.android.features.widget.impl"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

setupDependencyInjection()

dependencies {
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
    implementation(projects.services.appnavstate.api)
    implementation(libs.androidx.datastore.preferences)
    api(projects.features.widget.api)

    testCommonDependencies(libs, true)
    testImplementation(projects.libraries.sessionStorage.test)
    testImplementation(projects.libraries.matrix.test)
    testImplementation(projects.services.appnavstate.test)
}
