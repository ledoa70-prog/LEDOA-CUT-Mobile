# Isolated Android test harness — execution not completed

The target is the unchanged, signed release APK. A separate instrumentation package uses the same existing signing certificate and enables WebView inspection only for the isolated test process. The helper is not a release or user installation artifact. No key or APK is committed.

`original-media-test.cjs` defines original HEVC import, two representative 27.5-second boundaries, baseline-to-candidate project/media comparison, short native MP4 export observation, and restoring the compatibility fixture before rollback. **These Android tests have not run.** `native-attempt.json` records the initialization blocker. Do not interpret test source or a compiled helper as a pass.

Prerequisites:

- An isolated Android SDK/AVD and a host that can boot it. API 36 x86_64 without KVM did not finish booting in this attempt.
- SDK platform 35 and build tools 35.0.0 to compile/package the Java instrumentation source and manifest; sign it with the existing target certificate. The release APK is not modified to make it debuggable.
- Playwright in `CODEX_PRIMARY_RUNTIME_NODE_MODULES`; the controller and every adb/Playwright child must share the same local network namespace.
- The two privately supplied originals under `upload/`, baseline/candidate/rollback APKs under the task directories, and the synthetic blue image fixture. Never publish those media, private compatibility snapshots, keys, or raw project data.

Launch `native-controller.cjs` from the task working directory with Node's `--experimental-repl-await`. All adb calls explicitly select `emulator-5554`. Install baseline first, then the same-signed helper; start instrumentation, connect the WebView and attach `original-media-test.cjs` to `p0`. Capture baseline, candidate and rollback results separately. Pull any native MP4 and inspect actual decoded frames, audio/video duration and synchronization before setting an export result to pass.

Create a fresh AVD with system/vendor image sizes matching the official ZIP, avoiding stale qcow images. The default system partition size in an incomplete manual configuration truncated the API 36 system image during the initial setup; it was restored from the verified archive before the final attempt. This infrastructure issue was not an application failure.

Use `await p0.stop()` and `.exit` to terminate the owned emulator and adb server. This harness is not a command to operate on a user's phone. Actual-phone project backup and field verification remain separate requirements.
