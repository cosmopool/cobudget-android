plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// `make test T=Name` filters both modules at once: a module with no class matching the filter
// passes instead of failing the run.
subprojects {
    tasks.withType<Test>().configureEach { filter.isFailOnNoMatchingTests = false }
}
