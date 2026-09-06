# Warmly Solo 코드 재사용 결정

감사 기준: `warmly-solo`의 기준점 `182d1e5`와 후기 legacy/recovery 브랜치 `bce3126` 비교

## 기준점

`182d1e5`를 유지한다. 후기 브랜치의 약 3.5만 줄은 대부분 Lux 이전·세대·lease·복구 anchor·ownership 교대 문제를 풀며, Warmly Solo의 단일 수면 계획에는 필요하지 않다.

## 유지

- `WakeRamp.frameAt()`: 부드러운 빛·앱 재생 게인·진동 곡선
- 현재 `AlarmManager`·receiver·foreground service: 새 단일 scheduler의 구현 출발점
- `WakeAudioStore`의 임시 파일 복사 후 게시 원칙: 다중 track 저장소로 다시 설계
- `GentleWakePreview`의 일출 표현과 미리 체험 개념
- 기존 알람 서비스의 잠금화면·종료·기본음 fallback 테스트 중 단일 계획에도 유효한 부분

## 알고리즘·테스트만 선택적으로 이식

- 후기 `WakeTimeline.resolveGoal()`의 DST gap/overlap 해석
- 자정, DST 변경, 시간대 변경 테스트
- `AlarmManager.setAlarmClock()`을 감싸는 작은 주입 경계와 실제 등록 Robolectric 테스트
- 큰 글씨 2배, 최소 터치 높이, 접근성 순서 테스트 패턴
- 저장된 계획과 편집 초안 비교 및 변경 취소 확인 패턴

후기 파일 전체를 cherry-pick하지 않고 새 `SleepPlan` 이름과 계약으로 다시 구현한다.

## 다시 설계

- `WakeRoutine`·stage·preset 여러 모델 → 하나의 `SleepPlan`
- 고정 `selected-audio` 파일 → stable track ID와 순서를 가진 로컬 플레이리스트
- 알람 목록 DB → 새 설치 전용 v1 DB의 단일 계획·track·최소 active-run 상태
- scheduler → ramp START와 목표 시각 GOAL backup 두 개만 예약
- receiver → 신뢰할 수 있는 내부 action과 최소 occurrence ID만 검증
- service → 중복 START에 멱등하고 `elapsedRealtime` 기준으로 램프를 진행하며 항상 정리
- 재부팅이 램프 중간에 일어나면 목표 시각을 버리지 않고 남은 램프를 늦게 시작

## 제외

- 모든 `Legacy*` 구현·테스트·schema provenance
- V6 migration과 coordinator table
- generation, install epoch, handoff, rollback
- recovery anchor, lease, heartbeat, outbox, canonical wire protocol
- `PrimaryWakeScheduleCoordinator`, `WakeOrchestrator`, legacy audio bootstrap
- 후기 reliability commit의 wholesale cherry-pick

## 다음 신뢰성 gate

새 scheduler를 수락하기 전에 다음을 테스트한다.

1. 자정 경계와 반복 요일
2. DST gap/overlap
3. 시간·시간대 변경
4. 램프 시작 후 앱 또는 기기가 재시작된 경우의 late start
5. START 중복 수신 멱등성
6. 목표 시각 GOAL backup이 음악 성공과 독립적으로 실행되는지