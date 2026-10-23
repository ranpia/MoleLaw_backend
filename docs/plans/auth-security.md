# 인증·운영 보안 후속 계획

연결 작업: [P08](README.md). 현재 계약은 [Access](../stateless-access.md), [Refresh](../refresh-rotation.md), [사용자 검증](../user-account-validation.md)이다. 과거 Redis Access·JWT Refresh·local 15시간 설계는 현재 목표가 아니다.

## 현재 보장

모든 프로필에서 Access는 최대 5분 stateless JWT이며 User.id·버전 3·HS256·issuer/audience·용도·수명을 검증한다. Refresh는 Redis 난수 토큰 해시·family 절대 7일·원자 회전·재사용 탐지·폐기를 사용하며 HttpOnly 쿠키 전용이다.

로그아웃·비밀번호 변경 후 복사된 Access는 만료까지 최대 5분 유효하다. Redis 장애는 로그인·재발급·폐기에 영향을 주지만 Access의 토큰 검증은 Redis를 조회하지 않는다. 사용자 존재·권한·상담방 소유권은 별도로 검사한다.

## 실제 브라우저 검증

로컬 HTTP와 운영 HTTPS에서 가입·로그인·Google OAuth·재발급·로그아웃·비밀번호 변경·탈퇴를 확인한다. 쿠키의 Secure·HttpOnly·SameSite·Path·삭제와 명시적 Origin 허용을 점검한다.

프론트는 GET /api/auth/csrf의 JSON 토큰을 읽어 상태 변경 요청 헤더와 credentials에 전달한다. 운영 CSRF 쿠키는 HttpOnly다. 다중 탭 refresh 단일화와 응답 유실 후 이전 Refresh 자동 재시도 금지를 확인한다. Swagger 활성화를 위해 운영 보안 정책을 완화하지 않는다.

## 운영 복구·보호

| 작업 | 확인할 실패 경로 |
| --- | --- |
| Redis 영속화·복원 | 유실 Refresh 재로그인, 오래된 백업의 폐기 family 부활 방지 |
| 계정 변경·토큰 발급 | MySQL/Redis 부분 실패·동시 회전·비밀번호 변경 경합 |
| 호출 제한 | 로그인·재발급·가입·수집 남용, 제한 초과와 저장소 장애 구분 |
| 키 교체 | JWT·상담 암호화 키 버전, 구 기록 복호화·전환 재로그인 |
| 배포 | TLS·비밀값·헬스체크·롤백·local/test 프로필 금지 |

복구·보존·개인정보 정책은 D09·D10에서 확정한다. 과거 보안 감사의 미해결 목록을 현재 취약점으로 그대로 사용하지 않고 코드·테스트·브라우저 증거로 다시 판정한다.

## 완료 조건

단위·독립 Redis/MySQL·실제 브라우저·운영 복구를 구분해 보고한다. 보안 필터 실패 응답과 API JSON 응답의 차이를 프론트와 확인한다. 운영 배포는 검증 후 main의 수동 워크플로로 실행한다.
