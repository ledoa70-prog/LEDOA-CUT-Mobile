# LEDOA AI 얼굴 연속 추적 v0.8.5

Standalone ARM64 Android preview patch based on v0.8.4 (27c4403). Package: kr.ledoa.cut.aiface085. Existing LEDOA CUT and v0.8.4 remain installed. No MP4 export.

Changes:
- Sequential MediaCodec decoding with actual timestamps, crop/rotation/YUV strides and BT.709/601 color handling; one-time MediaMetadataRetriever fallback.
- Lightweight grayscale samples every 50ms; accurate face model around 100ms. Pose intervals 900ms for stable detections / 300ms when the target becomes difficult.
- Independent head motion may cover detector misses even if background faces are detected. Masks remain provisional and never update the identity gallery.
- At most 1800ms of unverified motion and 40 small grayscale frames. Forward/backward repair requires closure onto established track evidence.
- Preserve the real candidate boxes leading to multi-frame reacquisition.
- Preserve fractional end coverage, elapsed time and review summary. Export separate uncovered and provisional counts, decoder mode and timing breakdown.
- Serialize detector cleanup after in-flight processing; keep screen awake during analysis.

Build: Gradle 8.9, Java 17, Android SDK 35, `cd project && gradle :app:assembleDebug`. CI builds and signs a separate v0.8.5 package using the existing repository debug signing key.

Validation limitations: JVM tests and local replay of the submitted v0.8.4 detector decisions do not execute ML Kit or MediaCodec on the user's Fold6. Real device runtime, exact processing speed and final detection coverage require a device run. Customer video, per-frame coordinates and face descriptors are not committed.
