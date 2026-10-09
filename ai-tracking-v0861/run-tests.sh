#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
HERE="$ROOT/ai-tracking-v085"
SRC="$HERE/project/app/src/main/java/kr/ledoa/cut/aitest"
OUT="${LEDOA_TEST_OUT:-$HERE/test-out}"
mkdir -p "$OUT"
javac -d "$OUT" "$SRC/FacePath.java" "$SRC/TrackWindow.java" "$ROOT/ai-tracking-v0861/TrackWindowV0861Test.java" "$SRC/FaceAppearance.java" "$SRC/BodyClothing.java" "$SRC/FaceRoi.java" "$SRC/OpticalBridge.java" "$SRC/LazyFlowBridge.java" "$SRC/ContinuityBridge.java" "$SRC/SafeGapV086.java" "$ROOT/ai-tracking-v086/SafeGapV086Test.java" "$ROOT/ai-tracking-v073/FacePathV073Test.java" "$ROOT/ai-tracking-v074/FacePathV074Test.java" "$ROOT/ai-tracking-v075/BodyClothingV075Test.java" "$ROOT/ai-tracking-v076/FaceBodyV076Test.java" "$ROOT/ai-tracking-v077/FaceRecoveryV077Test.java" "$ROOT/ai-tracking-v078/FaceRoiV078Test.java" "$ROOT/ai-tracking-v079/FaceStabilityV079Test.java" "$ROOT/ai-tracking-v084/FaceCrowdV084Test.java" "$HERE/ContinuityV085Test.java"
for TEST in FacePathV073Test FacePathV074Test kr.ledoa.cut.aitest.BodyClothingV075Test kr.ledoa.cut.aitest.FaceBodyV076Test kr.ledoa.cut.aitest.FaceRecoveryV077Test kr.ledoa.cut.aitest.FaceRoiV078Test kr.ledoa.cut.aitest.FaceStabilityV079Test kr.ledoa.cut.aitest.FaceCrowdV084Test kr.ledoa.cut.aitest.ContinuityV085Test kr.ledoa.cut.aitest.SafeGapV086Test kr.ledoa.cut.aitest.TrackWindowV0861Test; do
    java -cp "$OUT" "$TEST"
done
