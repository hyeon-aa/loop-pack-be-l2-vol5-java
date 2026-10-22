# 5주차 구현 기록

최종 작업 내용을 기능·검증 목적별 커밋으로 정리한다.

| 순서 | 변경 |
| --- | --- |
| 1 | feat: 주문 예약과 결제 시도 모델 추가 |
| 2 | test: 주문 예약 상태 전이 검증 |
| 3 | test: 결제 시도의 종결·중복·충돌 규칙 검증 |
| 4 | feat: 결제 시도와 관찰 이력 저장 구현 |
| 5 | test: 결제 시도 저장과 유일 제약 검증 |
| 6 | test: 장애를 제어할 수 있는 가짜 PG 서버 추가 |
| 7 | feat: 테스트 PG HTTP 어댑터 구현 |
| 8 | feat: PG 조회 재시도·회로·fallback 구현 |
| 9 | feat: PG 제공자 빈과 조회 정책 설정 연결 |
| 10 | test: PG 생성·조회 HTTP 계약 검증 |
| 11 | test: 조회 정책 설정값 검증 |
| 12 | test: 조회 재시도 조건과 시간 예산 검증 |
| 13 | test: 조회 회로 차단과 회복 검증 |
| 14 | feat: 주문 확정을 PG 결제로 전환하고 결과를 분리 반영 |
| 15 | test: 주문 결제 성공·실패·응답 유실 검증 |

## 커밋별 실행 검증

별도 검증 폴더에서 현재 커밋에 들어가는 파일만 구성한 뒤 컴파일·Checkstyle·ArchUnit과 추가한 테스트를 확인한다. 각 검사의 실제 결과는 이 절에 누적한다.

| 커밋 | 종료 결과 | 일반 테스트 수 | 실험 테스트 수 |
| --- | --- | --- | --- |
| 01 | BUILD SUCCESSFUL | 1 | 0 |
| 02 | BUILD SUCCESSFUL | 11 | 0 |
| 03 | BUILD SUCCESSFUL | 27 | 0 |
| 04 | BUILD SUCCESSFUL | 1 | 0 |
| 05 | BUILD SUCCESSFUL | 4 | 0 |
| 06 | BUILD SUCCESSFUL | 2 | 0 |
| 07 | BUILD SUCCESSFUL | 1 | 0 |
| 08 | BUILD SUCCESSFUL | 1 | 0 |
| 09 | BUILD SUCCESSFUL | 1 | 0 |
| 10 | BUILD SUCCESSFUL | 32 | 0 |
| 11 | BUILD SUCCESSFUL | 2 | 0 |
| 12 | BUILD SUCCESSFUL | 10 | 0 |
| 13 | BUILD SUCCESSFUL | 7 | 0 |
| 14 | BUILD SUCCESSFUL | 52 | 0 |
| 15 | BUILD SUCCESSFUL | 13 | 0 |

각 단계에서 compileTestJava·Checkstyle main/test·ArchUnit을 실행했다. 테스트 수는 해당 단계에서 선택한 테스트 수이며 전체 누적 커버리지 수가 아니다. 비교 실험은 실험 전용 작업으로 실행했다.
