plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

// vpnLib contains Kotlin as well as Java sources. Apply the Kotlin Android
// plugin to the upstream library subproject before its build script is evaluated.
project(":vpnLib") {
    apply(plugin = "org.jetbrains.kotlin.android")
}
