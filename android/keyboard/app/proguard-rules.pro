# Keep the IME entry point and the PGP bridge contract for future UniFFI/JNI wiring.
-keep class com.newpgp.keyboard.NewPgpImeService { *; }
-keep interface com.newpgp.keyboard.PgpBridge { *; }
-keep class com.newpgp.keyboard.MockPgpBridge { *; }
-keep class com.newpgp.keyboard.NativePgpBridge { *; }

# The autocorrect engine is pure Kotlin; keeping it avoids reflection surprises in tests.
-keep class com.newpgp.keyboard.autocorrect.** { *; }
