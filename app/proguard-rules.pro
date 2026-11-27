# JNI_OnLoad looks this class up by name and registers every native method by
# name, so R8 must not rename the class or remove a member it thinks is unused.
-keep class com.corejbh.hashes.core.NativeBridge { *; }

# The mining threads are plain threads; nothing here needs reflection.
