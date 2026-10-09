#!/usr/bin/env bash
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
SRC="$HERE/project/app/src/main/java/kr/ledoa/cut/aitest"
OUT="$HERE/test-out"
mkdir -p "$OUT"
javac -d "$OUT" "$SRC/FacePath.java" "$SRC/FaceAppearance.java" "$SRC/BodyClothing.java" "$SRC/FaceRoi.java" "$SRC/OpticalBridge.java" "$SRC/LazyFlowBridge.java" "$SRC/ContinuityBridge.java" "$SRC/SafeGapV086.java" "$SRC/TrackWindow.java" "$SRC/SceneCuts.java" "$SRC/MosaicTimeline.java" "$HERE"/tests/*Test.java
for TEST in FacePathV073Test FacePathV074Test kr.ledoa.cut.aitest.BodyClothingV075Test kr.ledoa.cut.aitest.FaceBodyV076Test kr.ledoa.cut.aitest.FaceRecoveryV077Test kr.ledoa.cut.aitest.FaceRoiV078Test kr.ledoa.cut.aitest.FaceStabilityV079Test kr.ledoa.cut.aitest.FaceCrowdV084Test kr.ledoa.cut.aitest.ContinuityV085Test kr.ledoa.cut.aitest.SafeGapV086Test kr.ledoa.cut.aitest.TrackWindowV0861Test kr.ledoa.cut.aitest.IdentityGuardV0862Test kr.ledoa.cut.aitest.RecoveryV0863Test kr.ledoa.cut.aitest.MultiviewV0864Test kr.ledoa.cut.aitest.MosaicV090Test; do java -cp "$OUT" "$TEST"; done
