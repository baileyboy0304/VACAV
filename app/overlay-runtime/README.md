# Fire TV runtime in the unified VACAV app

Imported from the supplied Windows `VACA_Vision_App/overlay`, the Vision-adapted
continuation of `V13Overlay`. It retains the original dashboard, picker, BLE remote,
alarm and Home-dismissal implementations, with shared Vision protocol and audio
sources from the current VACAV tree. Classes use `com.mph1970.vacavision.overlayruntime`
and resources use `ov_` prefixes so the ES8 implementation keeps its original names.

This is a source set of `app/standard/app`, not a separate APK or Gradle build.
The main manifest registers these components. `DeviceMode` selects the runtime;
the original standard activity and services remain responsible for ES8 behavior.
The old picker helper is replaced by an activity alias in the same APK.
Original component licenses and credits remain in the imported assets.
