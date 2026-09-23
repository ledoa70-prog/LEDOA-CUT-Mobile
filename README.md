# LEDOA CUT Mobile Shorts Standalone v0.3.1

모바일에서 **PC 없이 쇼츠/카카오 게시물용 짧은 영상**을 빠르게 편집하기 위한 전용판입니다.

## 제품 방향

- PC 서버 / IP 주소 입력 없음
- 음성 자동인식(Whisper) 제외
- 9:16 쇼츠를 기본 화면으로 시작
- 화면 전환은 미리 다음 클립을 준비한 뒤 Canvas 합성으로 처리
- 큰 미리보기 + 손가락 조작용 단순 타임라인
- 썸네일/표지 지정
- 쇼츠 템플릿
- 미디어 / 템플릿 / 클립 / 문구 / BGM 5개 메뉴만 하단 고정

## Android 독립 실행형

`android/` 폴더를 GitHub Actions가 자동 빌드하여 설치 가능한 APK를 만듭니다.

- WebView 안에서 편집기를 로컬 실행
- 갤러리/파일 선택 지원
- 영상/표지 파일을 Android MediaStore로 저장
- 영상: `Movies/LEDOA CUT`
- 표지: `Pictures/LEDOA CUT`
- 인터넷 권한 없음
- Android 10 이상

## 모바일판에서 제외한 기능

자동 음성인식은 의도적으로 제외했습니다. 자동 자막이 필요한 롱폼 제작은 PC용 LEDOA CUT에서 처리합니다.
