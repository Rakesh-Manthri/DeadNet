# Project DeadNet Proguard / R8 Configuration

# Room Database
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Bouncy Castle Cryptography
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Protocol Data Classes & Models
-keep class org.deadnet.protocol.** { *; }
-keep class org.deadnet.data.** { *; }
