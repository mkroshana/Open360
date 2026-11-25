# ViewModels and AndroidViewModels instantiated via reflection by ViewModelProvider / viewModels()
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    public <init>(android.app.Application);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle);
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>();
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(androidx.lifecycle.SavedStateHandle);
}

# Services invoked by Android and Google Play services
-keep class com.open360.core.gps.GpsTrackerService { *; }
-keep class com.open360.core.watch.WatchTrackReceiver { *; }

# Models and enums
-keep class com.open360.core.model.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Optional OkHttp / JVM TLS providers not present on Android
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
