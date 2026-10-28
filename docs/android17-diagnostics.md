# Galaxy Z Fold7 Android 17 diagnostic build

Target device: SM-F966U, One UI 9.0, Android 17 (build CP2A.260605.016.F966USQSDCZJ1).

Observed: accessibility overlay enabled, test animation works, hinge angle absent; capture process reports `live capture stopped`.

This branch temporarily retains up to 12 lines of live-capture process output when it exits, to expose root cause. No permissions should be broadened based on this diagnosis alone.

After GitHub Actions builds the debug APK, test only on a consenting device. Record any exact error message, excluding personal data, and use it to develop a minimal fix. Preserve upstream license and attribution.
