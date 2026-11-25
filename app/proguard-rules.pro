# Shrink only the libraries; the app's own code is kept as-is.
# - No renaming: stored data uses enum / class names (events, marks, settings, backups).
# - Receivers, services, workers and the tile are referenced from the manifest / by name.
-dontobfuscate
-keep class com.attendance.tracker.** { *; }
-keepclassmembers enum * { *; }

# Libraries that reference optional classes
-dontwarn org.osmdroid.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
