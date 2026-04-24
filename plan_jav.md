# Java / Android Build Plan

Use this on a machine that has Android Studio installed.

1. Install Android Studio.
2. Open this project folder in Android Studio.
3. Install a JDK 17 Android Studio bundle if prompted.
4. Ensure `gradle.properties` points to a valid JDK.
   - If needed, replace `org.gradle.java.home` with the real Android Studio `jbr` path.
5. Sync the project.
6. Run `assembleDebug`.
7. Test send/receive on device and verify logs show decoded bitframes.

Notes:
- This machine currently has no valid JDK 17 / Android Studio path for Gradle.
- The code changes in this branch are already aligned to the receive pipeline fix.
