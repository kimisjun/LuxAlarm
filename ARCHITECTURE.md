# GentleWake 기술 설계

작성: 2026-09-03

## 제품 원칙

- 놀라게 하는 단일 경보보다 `어두운 빛 → 음악 → 진동 → 목표 시각 백업 경보`의 단계적 루틴을 우선한다.
- 기본 동작은 네트워크·Hermes·스트리밍 서비스 없이 휴대폰 단독으로 완료된다.
- 가져온 음원은 앱 관리 저장소로 복사해 원본 파일 위치가 바뀌어도 유지한다.
- 실제 알람 성공 여부와 사용자의 `일어났어요` 확인을 분리해 기록한다.
- Lux 미션은 알람별 선택 옵션이며 센서가 없거나 권한이 막히면 안전하게 기상 확인 버튼으로 돌아간다.

## MVP 기본 프로필

| 단계 | 목표 시각 기준 | 화면 | 음악 | 진동 |
|---|---:|---|---|---|
| 새벽빛 | -20분 | 거의 검은 암갈색 | 5% | 없음 |
| 여명 | -15분 | 낮은 주황빛 | 12% | 없음 |
| 일출 | -10분 | 따뜻한 주황빛 | 20% | 약함 |
| 기상 유도 | -5분 | 밝은 크림빛 | 28% | 중간 |
| 목표 시각 | 0분 | 밝은 일출빛 | 최대 35% | 뚜렷함 + 백업 경보 |

수치는 사용자 설정으로 변경 가능하고, 보간은 계단식이 아니라 매끄러운 곡선을 사용한다.

## 공통 도메인 모델

```text
WakeAlarm
- id
- time / repeatDays / enabled
- rampDuration
- startVolume / maxVolume
- sound: bundled | imported
- vibrationCurve
- sunrisePalette
- dismissal: confirm | lux(threshold, holdDuration)
- fallbackAlarmEnabled

WakePhase
- sleeping / dawn / sunrise / prompting / fallback / completed

WakeRun
- scheduledAt / startedAt / targetAt
- phase / completedAt / completionMethod
- lastFailure
```

공통으로 공유하는 것은 JSON 규격과 테스트 벡터이며, 플랫폼 알람 실행 코드는 네이티브로 분리한다.

## Android

- 기반: Lux Alarm 2.4.1 (`147ea5c4ce4ea416a1d02975754cc12496e73433`), GPLv3
- Kotlin + Jetpack Compose + Room
- `AlarmManager` 정확 알람으로 램프 시작과 목표 시각 백업을 각각 예약
- foreground service가 오디오·화면·진동 램프를 한 실행으로 소유
- 재부팅·시간대 변경·앱 업데이트 후 재예약
- full-screen alarm Activity에서 sunrise 화면과 종료/Lux 미션 제공
- 모든 새 기능은 기존 테스트 스위트에 RED→GREEN으로 추가

## iOS

- SwiftUI + AlarmKit(iOS 26+)
- AlarmKit 권한, 반복/고정 알람, 잠금화면 알람 UI, 목표 시각 백업을 사용
- 앱이 전면/bedside 상태일 때 sunrise 화면과 연속 램프를 완전 제공
- 백그라운드에서 가져온 긴 음악의 연속 점진 재생 가능 범위는 공식 API 검증 결과에 따라 제한을 UI에 명시
- Android GPL 코드는 복사하지 않고 본 문서의 독립 규격으로 구현

## 상태 소유권

- 알람 설정·가져온 음원·실행 이력: 각 휴대폰 로컬 앱이 권위자
- Hermes 연동: 2단계의 선택 기능이며 알람 성공 조건이 아님
- 스마트 조명: 2단계
- 건강 추론·수면 진단: 범위 밖

## 검증

### 공통
- `wake-ramp-test-vectors.json`의 공통 벡터를 Android/iOS 모두 통과
- 0%, 25%, 50%, 75%, 100% 진행도에서 빛·음량·진동 곡선
- 시간대/DST/자정 경계와 반복 요일
- 중복 실행 방지와 `일어났어요` idempotency
- 가져온 음원 누락 시 기본 소리 fallback

### Android
- clean unit test + APK build
- emulator와 Galaxy 실기기에서 앱 종료·화면 잠금·재부팅·Doze 조건
- 실제 음량/화면/진동 단계와 Lux 종료

### iOS
- XCTest + simulator build
- AlarmKit 권한 거부/승인, 예약 read-back
- iPhone 실기기에서 잠금·무음·집중모드·앱 종료 조건
- 앱 전면 bedtime 모드와 목표 시각 시스템 fallback 구분
