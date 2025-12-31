plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // Google services Gradle plugin (Firebase)
    id("com.google.gms.google-services") version "4.4.2" apply false
    // KSP for Room
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
