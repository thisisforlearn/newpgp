# Add project-specific ProGuard / R8 rules here.
# Release builds currently have minifyEnabled = false, so these are a no-op.

# Keep the app's entry points (Compose and the share target are discovered by
# reflection from the manifest / generated code).
-keep class com.newpgp.app.MainActivity { *; }
-keep class com.newpgp.app.ShareActivity { *; }

# Phase 2 UniFFI wiring: when generated Kotlin bindings and JNI libraries are
# added, keep the generated API surface and any native entry points, e.g.:
#   -keep class newpgp.core.** { *; }
#   -keepclasseswithmembernames class * { native <methods>; }
