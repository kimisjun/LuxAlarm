# Warmly Solo 기술 설계

작성: 2026-09-06

## 제품 경계

Warmly Solo는 Android에서 하나의 활성 수면 계획을 관리하는 로컬 우선 기상 앱이다. 현재 구현은 첫 수면 계획의 입력·저장·홈 표시까지이며, 실제 알람 예약과 기상 실행은 아직 연결하지 않았다.

## 출처와 라이선스

- 기반: Lux Alarm 2.4.1 by Daniel Salmun, upstream commit `147ea5c4ce4ea416a1d02975754cc12496e73433`
- 라이선스: GPL-3.0-or-later
- Warmly 수정 범위와 현재 비구현 기능: `NOTICE.md`
- 새 application ID: `com.kimisjun.warmly`

## 현재 첫 슬라이스

```text
WarmlyOnboardingScreen
  → 사용자 기상 시각
  → 취침 추천 3개 또는 직접 설정
  → SleepPlan
  → RoomSleepPlanStore
  → WarmlyDatabase(singleton row)
  → 저장된 계획 홈 요약
```

- `SleepPlan`: 기상 분, 취침 분, 취침 날짜 offset
- `SleepPlanEntity`: 기본키가 1인 단일 행
- `SleepPlanStore`: UI와 Room 사이의 suspend 저장 경계
- `LuxAlarmApp`: Room load 완료 후 온보딩 또는 저장된 홈으로 라우팅
- 기존 Lux 다중 알람 DB는 Warmly 시작 경로에서 열지 않는다.
- 기존 `BootReceiver`와 `RescheduleReceiver`는 manifest에서 비활성화했다.

## 다음 슬라이스

1. 로컬 플레이리스트와 앱 관리 음원 사본
2. 권한별 이유 설명 및 준비 상태
3. 시험 알람
4. `AlarmManager`의 램프 시작·목표 시각 백업 예약
5. foreground service의 빛·음악·진동 실행
6. `일어났어요` 종료
7. 재부팅·시간대 변경·앱 업데이트 후 단일 계획 재예약

Room이 권위 상태이며 AlarmManager 등록은 재생성 가능한 projection으로 유지한다.

## 검증 원칙

- 단위·Compose·Room 재개방 테스트
- lint, release Kotlin compile, Spotless, APK build
- 실제 예약을 연결한 뒤 Galaxy에서 앱 종료·잠금·재부팅·Doze canary
- 실제 기기 canary 이전에는 “알람 준비됨” 또는 “예약 완료”라고 표시하지 않는다.
