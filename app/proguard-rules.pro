# JNI resolves these methods by the class and method names at call time, so R8
# must not rename the class or drop a member. The default Android rules already
# keep classes with native methods; this makes it explicit and survives a change
# to the default rules.
-keep class com.corejbh.hashes.engine.NativeMiner { *; }
