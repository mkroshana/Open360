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

# Foreground GPS service referenced from manifests and intents
-keep class com.open360.core.gps.GpsTrackerService { *; }

# Shared models and enums serialized across Data Layer / preferences
-keep class com.open360.core.model.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep enum com.open360.core.peer.PeerDevice { *; }
-keep enum com.open360.core.ble.CameraMode { *; }
-keep enum com.open360.core.ble.BleConnectionStatus { *; }
-keep enum com.open360.core.ble.Go2BleProtocol$CardState { *; }
-keep enum com.open360.ui.camera.CameraButton { *; }
-keep enum com.open360.core.sensors.SensorKind { *; }

# Optional OkHttp / JVM TLS providers not present on Android
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
