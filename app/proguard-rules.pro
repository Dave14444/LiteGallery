# LiteGallery release rules
#
# Keep the classes in their packages (R8 of AGP 9.1+ would move them all into one by default):
# behaves as before, and error reports stay readable.
-dontrepackage
#
# Remove low-value debug/info/verbose logs in release builds to reduce runtime overhead
# and avoid noisy output on user devices.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# SMB-specific keep rules live in proguard-smb.pro, applied only to the `plus` flavor.

# Screens of the settings are created by name from XML (preference "fragment" attributes).
-keep class * extends androidx.fragment.app.Fragment { <init>(); }
-keep class * extends androidx.preference.PreferenceFragmentCompat { <init>(); }
