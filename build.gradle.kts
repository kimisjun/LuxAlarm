// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.spotless)
}

spotless {
    ratchetFrom("HEAD")
    kotlin {
        target("app/src/**/*.kt")
        // Copyright provenance differs per file (upstream-modified vs GentleWake-new).
        // Keep headers explicit and inventory-validated instead of forcing one global owner.
        ktfmt().kotlinlangStyle()
    }
}
