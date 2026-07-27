# NovaVPN ProGuard Rules

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.novavpn.**$$serializer { *; }
-keepclassmembers class com.novavpn.** {
    *** Companion;
}
-keepclasseswithmembers class com.novavpn.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Timber
-keep class timber.log.Timber { *; }

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep models used in serialization
-keep class com.novavpn.domain.model.** { *; }
-keep class com.novavpn.data.local.db.entity.** { *; }
