# NyaayKhel ProGuard rules
# Keep TFLite classes
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Keep Room generated classes
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Keep Gson serialisation targets
-keep class com.nyaaykhel.app.data.** { *; }
-keepclassmembers class com.nyaaykhel.app.data.** { *; }
