# LEDOA AI FACE v0.8.6.3 guarded recovery

Base: v0.8.6.2 identity guard; v0.8.6.1 full Android source was recovered from GitHub feature/ai-face-v086-range-main.

## Evidence and motivation
User trace named v0.8.6.1 (1) contains IDENTITY_GUARD_ORIGINAL_MISMATCH_REVIEW. 18,595ms video had 169 review points and 151 uncovered samples; the final TRACKED sample was at 1,700ms. This is a regression in recoverability, not a crash.

## Changes
- Remain anchored to the first user-selected descriptor (unchanged).
- Allow a .73–.78 original similarity face to be re-acquired ONLY with >=3 sequential real detections, one face candidate in the current frame, and a geometrically consistent optical-flow estimate within 1.1s.
- Optical flow cannot self-authorize a person switch. Flow-guided re-acquisition is REVIEW REQUIRED even if recorded as TRACKED.
- Block extreme area jumps, no optical flow, ambiguous crowds; keep v0.8.6.2 identity guard tests.
- Log flow-guided recovery and guard rejection counts; accurately label diagnostics v0.8.6.3.
- Continue to preserve independent v0.8.6.1 and 0.8.6.2 installs. Preview-only, not MP4 export.

## Validation
Regression script: bash ai-tracking-v0861/run-tests.sh
Prior guard: javac/run ai-tracking-v0862/IdentityGuardV0862Test.java
New recovery: javac/run ai-tracking-v0863/RecoveryV0863Test.java
Android CI: .github/workflows/build-ai-face-recovery-v0863.yml

Important: JVM simulation passes do not prove actual identity accuracy. Run an Android test using the original 18.6-second video, then examine uncoveredCount, identityGuardFlowRecoveries, and actual target boxes. Do not publish a privacy video unless gaps are checked.
