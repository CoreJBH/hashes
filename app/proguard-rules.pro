# JNI resolves these methods by the class and method names at call time, so R8
# must not rename the class or drop a member. The default Android rules already
# keep classes with native methods; this makes it explicit and survives a change
# to the default rules.
-keep class com.corejbh.hashes.engine.NativeMiner { *; }

# org.json is the platform parser in the app and the real library in JVM tests;
# R8 sees the test-only artifact in its input set and would warn about it.
-dontwarn org.json.**

# CameraX and ZXing ship their own consumer rules; nothing here needs to keep
# their classes by name, and the QR path is called directly (no reflection).
