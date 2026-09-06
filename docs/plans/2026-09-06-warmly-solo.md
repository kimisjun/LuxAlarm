# Warmly Solo Implementation Plan

> **For Hermes:** 각 코드 작업은 RED→GREEN→REFACTOR와 2단계 검토를 거친다.

**Goal:** 하나의 수면 계획과 로컬 기상 플레이리스트를 중심으로 Galaxy-ready 부드러운 기상 앱을 만든다.

**Architecture:** Room을 권위 상태로 두고 `AlarmManager` 등록은 재생성 가능한 projection으로 유지한다. 앱 소유 로컬 음원, 짧은 receiver, foreground 재생 service, 전체 화면 기상 UI를 사용한다. legacy migration이나 다중 scheduler ownership 계층은 도입하지 않는다.

---

## Phase A — 제품 분리 (완료)

1. Lux와 별도 Warmly application ID·브랜딩·진입 흐름을 만든다.
2. 기존 다중 알람 DB와 boot/time-change rescheduling 진입점을 Warmly 경로에서 끊는다.
3. UI/UX 근거와 두 모바일 시안을 비교해 단일 계획의 디자인 언어를 정한다.

## Phase B — 단일 `SleepPlan` (완료)

1. 기상 시각, 취침 추천 3개, 직접 취침 시각을 구현한다.
2. Room singleton row에 하나의 계획을 저장한다.
3. 재실행 시 저장된 계획의 홈 요약을 표시한다.

## Phase C — 로컬 플레이리스트 (완료)

1. Room 기반 플레이리스트 생성·선택·편집과 정렬된 트랙을 구현한다.
2. 음원을 100 MiB 이내 content-addressed 앱 소유 불변 파일로 가져온다.
3. marker/staging/reconciliation과 process-wide coordinator로 파일·Room 경계를 보호한다.
4. content ID를 검증하는 `Find`와 기존 항목을 조기에 지우지 않는 `Replace`를 제공한다.
5. ViewModel `SavedStateHandle`로 편집·picker 상태를 복원한다.
6. 순차 미리듣기, 누락·오류 건너뛰기, 기본 알람음 fallback을 구현한다.

감사 기준 `4c23b86`: 자동화 테스트 491개, lint, Kotlin compile, APKs 통과. Phase C P0/P1 0개. 물리 기기 검증과 signed release는 포함하지 않는다.

## Phase D — 실제 기상 실행 (미구현)

1. 권한 준비 상태와 권한별 이유 설명을 제공한다.
2. 짧은 시험 알람을 추가한다.
3. 단일 계획의 램프 시작과 목표 시각 백업을 예약한다.
4. receiver에서 불변 plan/occurrence identity를 검증한다.
5. media 작업 전에 foreground service를 시작·승격한다.
6. 기존 ramp 모델로 화면 밝기·player gain·진동을 구동한다.
7. `일어났어요`가 모든 효과를 멱등하게 종료한다.
8. 재부팅·시간·시간대 변경·앱 업데이트·계획 수정 후 예약을 복구한다.

## Phase E — Galaxy canary와 private use (미수행)

1. 잠금, process kill, 재부팅, Doze, 권한 거부/재허용, 누락 트랙, 중복 delivery를 Galaxy에서 검증한다.
2. 여러 밤 private use로 missed/late alarm과 timing 근거를 기록한다.
3. 배포 후보를 서명하기 전에 전체 gate와 산출물 checksum을 다시 검증한다.
