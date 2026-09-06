# Warmly Solo 기술 설계

작성: 2026-09-06
현재 상태: Phase C 완료

## 제품 경계

Warmly Solo는 Android에서 하나의 활성 수면 계획과 로컬 기상 플레이리스트를 관리하는 로컬 우선 앱이다. 플레이리스트 편집과 미리듣기까지 구현했지만, 실제 알람 예약과 기상 실행은 아직 구현하지 않았다.

## 출처와 라이선스

- 기반: Lux Alarm 2.4.1 by Daniel Salmun, upstream commit `147ea5c4ce4ea416a1d02975754cc12496e73433`
- 라이선스: GPL-3.0-or-later
- Warmly 수정 범위와 비구현 기능: `NOTICE.md`
- application ID: `com.kimisjun.warmly`

## 단계별 경계

### Phase A — 제품 분리

- Lux와 별도 이름·application ID·진입 흐름을 만들었다.
- Warmly 시작 경로에서 Lux 다중 알람 DB를 열지 않는다.
- 기존 `BootReceiver`와 `RescheduleReceiver`는 manifest에서 비활성화했다.

### Phase B — 단일 `SleepPlan`

```text
WarmlyOnboardingScreen
  → 기상 시각
  → 취침 추천 3개 또는 직접 설정
  → SleepPlan
  → RoomSleepPlanStore
  → WarmlyDatabase(singleton row)
  → 저장된 계획 홈 요약
```

- `SleepPlanEntity`는 기본키 1의 단일 행이다.
- Room이 수면 계획의 권위 상태다.

### Phase C — 로컬 플레이리스트 (완료)

- Room이 플레이리스트, 정렬된 항목, 트랙 메타데이터, 기상용 선택의 권위 상태다.
- 가져온 음원은 SHA-256 기반 content-addressed ID를 사용하는 앱 소유 불변 파일이다.
- 파일 하나당 100 MiB 상한을 두고 MIME·일반 파일·경로 경계를 검사한다.
- durable pending marker와 staging 파일을 거쳐 게시하며, 시작 시 Room 참조와 대조해 중단된 가져오기와 미참조 파일을 보수적으로 정리한다.
- process-wide `WakeAudioTransactionCoordinator`가 가져오기와 권위 참조 snapshot/reconciliation을 직렬화한다.
- `Find`는 선택 파일의 content ID가 누락 트랙 ID와 같은지 확인한 뒤 복구한다. `Replace`는 새 고유 트랙 등록이 성공한 뒤에만 기존 항목을 제거하며, 실패·재시도 중 기존 항목을 보존한다.
- 플레이리스트 화면의 편집 위치, 다이얼로그, 삭제 확인, 진행 중 Import/Find/Replace는 ViewModel `SavedStateHandle`에 보존한다.
- 미리듣기는 정렬 순서대로 재생하고 완료 시 다음 트랙으로 진행한다. 누락·오류 트랙은 건너뛰며 모두 실패하면 기본 알람음으로 fallback한다.

## 아직 구현하지 않은 실행 경계

`AlarmManager` 등록은 Room에서 다시 만들 수 있는 projection이어야 하지만, 그 projection과 foreground 기상 실행 자체는 아직 없다. 따라서 현재 앱은 실제 알람을 예약하거나, 잠금 상태에서 램프를 시작하거나, 재부팅 후 예약을 복구한다고 주장하지 않는다.

## 검증 기록

감사 기준 commit: `4c23b86`

- P0/P1 독립 감사 기준 `4c23b86`: 자동화 테스트 491개 통과
- 최종 미리듣기 I/O 보정 통합본: 자동화 테스트 492개 통과
- lint, Kotlin compile, APKs 통과
- 최종 Phase C 감사: P0 0개, P1 0개
- 물리 Galaxy canary와 signed release는 수행하지 않음

## 다음 슬라이스

1. 권한 준비 상태와 권한별 이유 설명
2. 시험 알람
3. 단일 계획의 램프 시작·목표 시각 백업 예약
4. foreground service의 빛·음악·진동 램프
5. `일어났어요`의 멱등 종료
6. 재부팅·시간·시간대 변경 후 복구
7. Galaxy 실기기 canary: 앱 종료·잠금·재부팅·Doze·권한 거부/재허용

실기기 canary 전에는 “알람 준비됨” 또는 “예약 완료”라고 표시하지 않는다.
