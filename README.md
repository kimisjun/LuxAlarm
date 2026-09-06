# Warmly

**부드러운 기상, 기분 좋은 하루.**

Warmly는 Lux Alarm 2.4.1 by Daniel Salmun을 기반으로 2026년에 수정한 GPLv3 Android 앱입니다. 원 프로젝트와 수정 코드의 라이선스는 [LICENSE](LICENSE), 출처와 수정 범위는 [NOTICE.md](NOTICE.md)를 참고하세요.

## 현재 상태 — Phase C 완료

- Lux와 분리된 Warmly 제품 진입 흐름
- Room에 하나의 `SleepPlan` 저장 및 홈 요약
- 플레이리스트 생성·이름 변경·선택과 트랙 추가·삭제·재정렬
- content-addressed 앱 소유 음원 사본과 파일당 100 MiB 상한
- 중단된 가져오기를 위한 marker/staging/reconciliation
- 누락 트랙 `Find`, 안전한 `Replace`, ViewModel `SavedStateHandle` 복원
- 순차 미리듣기와 기본 알람음 fallback

P0/P1 감사 기준 `4c23b86`에서 자동화 테스트 491개, lint, Kotlin compile, APKs가 통과했고 Phase C P0/P1은 0개였다. 이후 미리듣기 파일 확인을 I/O dispatcher로 옮긴 최종 통합본은 자동화 테스트 492개를 통과했다. 물리 기기 검증과 signed release는 아직 수행하지 않았다.

## 아직 구현되지 않은 기능

- 권한 준비 상태와 시험 알람
- 실제 `AlarmManager` 예약과 목표 시각 백업
- foreground service의 빛·음악·진동 램프
- `일어났어요` 종료
- 재부팅·시간대 변경 후 재예약
- Galaxy 실기기 canary

HTML 파일은 제품 UI를 비교하기 위한 상호작용 **시안**입니다. 시안의 조작은 데이터를 저장하거나 알람·오디오를 실행하지 않습니다.

전체 제품 계약과 단계별 범위는 [WARMLY_SOLO.md](WARMLY_SOLO.md), [기술 설계](ARCHITECTURE.md), [구현 계획](docs/plans/2026-09-06-warmly-solo.md)에 기록되어 있습니다.
