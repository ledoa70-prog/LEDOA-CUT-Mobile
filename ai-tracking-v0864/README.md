# LEDOA AI FACE v0.8.6.4 multiview recovery + manual fallback

Source parent: feature/ai-face-recovery-v0863

## Evidence from 18.595s mobile trace
- v0.8.6.3: 187 track records, 151 uncovered, 169 review required, 0 flow-guided recoveries.
- Last TRACKED frame: 1,700ms. Provisional optical motion only 1,800–3,500ms; after 3,500ms there are practically no mask locations.
- 47 APPEARANCE_MISMATCH_REVIEW and 80 LONG_GAP_REIDENTIFICATION_REQUIRED cases. Crash log COMPLETE.

## Changes
- Collect bounded 200ms-apart verified early appearance views, never provisional or reidentified candidates.
- Frozen original face descriptor remains the identity reference.
- Allow multiple trusted views to SUPPORT three-detection reconfirmation only with one face candidate and recent geometrically compatible optical-flow evidence. Mark result FLOW_GUIDED_REACQUIRED_REVIEW.
- Still block unsupported strangers, sudden size jumps, and ambiguity. No unsafe forced continuation after flow expires.
- Add a "first uncovered" UI shortcut for selecting the real face at the gap and restarting only the remaining interval; earlier timeline is preserved.
- Export per-sample bestOriginalSimilarity / bestTrustedSimilarity (just scalar evidence; no face images or facial embedding vectors) to distinguish weak descriptor errors from geometry errors.

## Build and safety
- Android package kr.ledoa.cut.aiface0864 is separate from the earlier test versions.
- GitHub Actions in .github/workflows/build-ai-face-multiview-v0864.yml runs all previous JVM tests, MultiviewV0864Test and Android APK signature verification.
- This standalone tester does NOT export an anonymized MP4.
- Manual review is mandatory for every FLOW_GUIDED_REACQUIRED_REVIEW, UNCERTAIN, LOST or FLOW_ESTIMATED period. Successful compilation does not certify face privacy.
