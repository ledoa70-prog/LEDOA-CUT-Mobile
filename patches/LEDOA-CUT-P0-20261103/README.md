# LEDOA CUT Mobile P0 — 20261103 REV1

담당: (주) 레도아 개발실 Sol 6.1 / 검수: 기획실장 마실장

**상태: 재현된 모바일 재생 오류 수정·브라우저 회귀 완료. 설치용 APK 정식 배포 보류.**

Android 실기기 실행, 대표의 실제 프로젝트 백업·열기, 최종 MP4 출력 검증이 끝나지 않았다. Linux 소프트웨어 인코더의 기본 출력 시험도 품질 기준을 통과하지 않았다. 이 변경을 정식 완료 또는 APK 배포 승인으로 해석하지 않는다. PC 작업은 시작하지 않았다.

## 기준과 변경 범위

- 실제 기준: `LEDOA_CUT_Mobile_v0.8.11_SOURCE.zip`과 같은 버전의 배포 APK. `assets/app.js`가 APK 안의 파일과 일치함을 확인했다.
- 이 저장소 main의 마지막 기준은 v0.8.0이다. 따라서 오래된 main을 새 배포본으로 오인하지 않도록 **정확한 v0.8.11에 적용하는 검증된 차이 파일**을 이 디렉터리에 보관한다.
- `baseline.json`에 원본 APK·소스 묶음 및 변경 대상 파일의 SHA-256을 고정했다.
- 변경: `assets/app.js` 재생/준비 콜백, `index.html` 버전 표시, `sw.js` 자산 캐시 버전.
- DB 이름·버전·저장 형식·패키지·네이티브 코드·모델은 바꾸지 않았다. 새 편집 기능이나 UI 개편은 없다.

## 확인된 수정

1. 디코더 재로드 시 프레임 수 감소와 탐색 점프를 재생 진전으로 오인해 복구 기한을 계속 갱신하던 오류를 고쳤다. 기존 1.2/2.5/4.2/8.5초 단계는 늘리지 않았다.
2. 지난 클립의 준비 타이머가 `drawableVideo → ensureRuntime`을 통해 소스를 다시 붙이던 부작용을 제거하고, 현재 클립·위치·런타임·재생 상태를 검증하도록 했다.
3. 취소된 시작/복구 작업의 늦은 metadata 콜백이 새 세션을 탐색하거나 폐기된 런타임을 다시 재생하지 못하도록 소유권과 세션을 검사한다.

자세한 보고는 [진단](01-mobile-diagnosis.md), [수정](02-mobile-fix.md), [배포 검증](03-mobile-release-gate.md), [PC 보류](04-pc-deferred.md)에 있다.

## 재현

작업 폴더에 다음을 준비한다. 실제 프로젝트나 사용자 영상은 테스트에 쓰지 않았다.

```text
p0_source_baseline/LEDOA_CUT_Mobile_v0.8.11_SOURCE/  원본 소스 압축 해제
p0_mobile/                                     원본 소스의 별도 복사본
p0_tests/fixtures/portrait30.mp4                 아래 명령의 합성 영상
p0_evidence/                                   시험 출력 폴더
p0_tools/chrome                                Chromium 또는 CHROME_BIN 지정
```

```sh
git -C p0_mobile apply --check /absolute/path/to/mobile-v0811.patch
git -C p0_mobile apply /absolute/path/to/mobile-v0811.patch
mkdir -p p0_tests/fixtures p0_evidence
ffmpeg -f lavfi -i testsrc2=size=720x1280:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 8 -c:v libx264 -preset ultrafast -crf 24 -pix_fmt yuv420p -c:a aac -movflags +faststart p0_tests/fixtures/portrait30.mp4
```

Node.js에 Playwright를 준비한 뒤 이 디렉터리의 `tests/*.cjs`를 작업 폴더에서 실행한다. `PLAYWRIGHT_MODULE`과 `CHROME_BIN`으로 설치 경로를 지정할 수 있다. 기본값은 Work 런타임의 Playwright와 작업 폴더의 Chromium이다.

```sh
node /absolute/path/to/tests/verify-mobile-p0.cjs
APP_ROOT=p0_source_baseline/LEDOA_CUT_Mobile_v0.8.11_SOURCE LABEL=before node /absolute/path/to/tests/diagnose-decoder-budget.cjs
APP_ROOT=p0_mobile LABEL=after node /absolute/path/to/tests/diagnose-decoder-budget.cjs
APP_ROOT=p0_source_baseline/LEDOA_CUT_Mobile_v0.8.11_SOURCE LABEL=before node /absolute/path/to/tests/diagnose-stale-start.cjs
APP_ROOT=p0_mobile LABEL=after node /absolute/path/to/tests/diagnose-stale-start.cjs
```

`verify-mobile-p0.cjs`는 MP4 영상 프레임 수와 영상/음성 길이까지 검사한다. 이 환경의 실제 결과는 **MP4 품질 게이트 FAIL / 전체 종료 코드 1**이며, 통과로 숨기지 않았다. 미리보기 108회 전환·100회 탐색 및 프로젝트 호환성 결과는 별도 항목에 남는다.

`tests/build-candidate.py`는 원본 v0.8.11 APK, 수정 소스, 기존 서명 개인키·인증서, apksigner를 명시적으로 받아 미배포 후보만 만든다. 키·APK·사용자 녹화·원본 미디어는 이 변경에 포함하지 않았다. 생성은 Android 실행 시험의 대체가 아니다.
