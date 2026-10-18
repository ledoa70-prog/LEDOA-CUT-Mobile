# w2v2-ko — 한국어 CTC 강제정렬 모델 (Apache-2.0 준수 고지)

> 이 파일은 **Apache-2.0 §4(b)의 "변경 사실 명시" 의무를 이행하는 문서다.**
> 우리는 원본 모델을 **수정해서(ONNX 익스포트 + int8 양자화) 재배포**하므로, 이 고지 없이는
> 상용 배포가 라이선스 위반이 된다.
>
> 배포처(HF 레포)의 모델 카드에도 **같은 내용이 들어가야 한다** —
> `scripts/publish-aligner-model.py`가 이 파일을 근거로 카드를 생성한다.

---

## 1. 원본 (Original Work)

| 항목 | 값 |
|---|---|
| 모델 | **`kresnik/wav2vec2-large-xlsr-korean`** |
| 출처 | <https://huggingface.co/kresnik/wav2vec2-large-xlsr-korean> |
| 라이선스 | **Apache-2.0** (모델 카드 메타데이터 `license: apache-2.0` — 2026-07-12 실물 확인) |
| 확인한 커밋 | `629c9a3501c10ba128bf3fa1eebb12af3be03f61` (lastModified 2023-07-03) |
| 학습 데이터 | `kresnik/zeroth_korean` (Zeroth-Korean) |
| 성능 (원저자 보고) | Test WER 4.74 / CER 1.78 |

### 베이스 모델 (Upstream of the original)

| 항목 | 값 |
|---|---|
| 모델 | **`facebook/wav2vec2-large-xlsr-53`** |
| 출처 | <https://huggingface.co/facebook/wav2vec2-large-xlsr-53> |
| 라이선스 | **Apache-2.0** (2026-07-12 실물 확인) |
| 확인한 커밋 | `c3f9d884181a224a6ac87bf8885c84d1cff3384f` |

> 라이선스 체인이 **Apache-2.0 → Apache-2.0** 로 일관된다. GPL/AGPL/NC 오염 없음.
> (`docs/rules/licensing.md` §1 — CC-BY-NC인 CrisperWhisper, AGPL인 whisper-timestamped와 대비된다)

---

## 2. 🚨 우리가 가한 변경 (Apache-2.0 §4(b) — Statement of Changes)

**movie-cut은 원본 가중치를 그대로 재배포하지 않는다. 아래 두 가지를 변경했다:**

| # | 변경 | 내용 | 왜 |
|---|---|---|---|
| 1 | **ONNX 익스포트** | PyTorch `Wav2Vec2ForCTC` → ONNX opset 17 (`model.fp32.onnx`). 동적 축(`samples`, `frames`), `attn_implementation="eager"`, `apply_spec_augment=False` | 런타임에서 **torch를 제거**하기 위해. onnxruntime으로 추론한다 (아키텍처 불변식 6 — 인스톨러 221MB 방어) |
| 2 | **int8 동적 양자화** | `onnxruntime.quantization.quantize_dynamic(weight_type=QInt8)` → `model.int8.onnx`. **1,267 MB → 318 MB (4.0배 축소)** | wav2vec2-large는 317M 파라미터 = fp32로 1.2GB. 사용자 디스크/다운로드 시간 방어 |

부수 산출물 (원본에서 **추출**한 것 — 가중치 변경 아님):

| 파일 | 내용 |
|---|---|
| `vocab.json` | 원본 토크나이저 vocab 1,207개 (한글 완성형 음절 1,202 + `\|` `[UNK]` `[PAD]` `<s>` `</s>`) |
| `meta.json` | `blank_id=1204`, `word_delimiter_token="\|"`, `do_normalize=true`, `total_stride_samples=320` (20ms/프레임) |

**변경하지 않은 것**: 가중치의 학습·파인튜닝을 **하지 않았다.** 아키텍처·vocab·전처리 규약은 원본 그대로다.
int8 양자화는 가중치의 수치 정밀도만 낮춘다 (재학습 아님).

재현 방법: `spikes/s4-onnx-align/export.py` (torch 필요 — **빌드 타임 전용**, 사용자 머신에 설치되지 않는다)

---

## 3. NOTICE 파일 (Apache-2.0 §4(d))

**원본 레포에는 `NOTICE` 파일이 없다** (2026-07-12 확인: `.../resolve/main/NOTICE` → HTTP 404).
`LICENSE` 파일도 레포에 없고, 라이선스는 **모델 카드 메타데이터**로만 선언되어 있다.

→ **보존해야 할 NOTICE가 존재하지 않으므로 §4(d)로 인한 추가 의무는 없다.**
   다만 §4(a)(라이선스 사본 동봉)와 §4(b)(변경 명시)는 그대로 적용되며, 이 폴더가 그 이행이다.

> ⚠️ 원본이 나중에 NOTICE를 추가할 수 있다. 모델을 **재익스포트할 때마다** 위 URL을 다시 확인하라.
> (`scripts/publish-aligner-model.py`가 업로드 전에 자동으로 확인하고 경고한다)

---

## 4. 우리가 이행하는 Apache-2.0 조건

| 조건 (Apache-2.0) | 우리 구현 |
|---|---|
| **§4(a)** 라이선스 사본 동봉 | [`LICENSE.txt`](./LICENSE.txt) (전문 202줄). `licenses/`는 인스톨러에 동봉된다 (`extraResources`) + HF 레포에도 올린다 |
| **§4(b)** 변경 사실 명시 | **이 문서 §2.** HF 모델 카드에도 동일 내용을 싣는다 |
| **§4(c)** 저작권/특허/귀속 고지 보존 | 원본에 별도 고지 파일 없음. 출처·저자(`kresnik`)·베이스(`facebook/wav2vec2-large-xlsr-53`)를 §1에 명시 |
| **§4(d)** NOTICE 보존 | 원본에 NOTICE 없음 (§3). 해당 없음 |
| 상표 (§6) | `kresnik`·Meta/Facebook의 이름을 **보증(endorsement)이나 제품명으로 쓰지 않는다.** 출처 표기 목적으로만 언급한다 |

---

## 5. 이 모델이 사용자에게 도달하는 경로

**인스톨러에 넣지 않는다.** Whisper·silero와 동일하게 **첫 사용 시 HF Hub에서 다운로드**한다
(`python/src/moviecut/aligner.py` `_ensure_model()` → `models_dir()/w2v2-ko/`).

→ 인스톨러 크기 증가 **0 MB**, 런타임 의존성 증가 **0개**.
→ 그럼에도 **우리가 재배포자**다 (우리 HF 레포에서 우리가 수정한 가중치를 내보낸다).
  따라서 위 Apache-2.0 조건은 **전부 우리 의무**다. 이 문서가 그 이행 기록이다.

---

[← licenses/models](../README.md) · [← licenses](../../README.md) · [규칙: docs/rules/licensing.md](../../../docs/rules/licensing.md)
