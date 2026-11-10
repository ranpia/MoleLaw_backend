# 인증·사용자

이메일·소셜 로그인과 사용자 계정 관리의 인증 계약을 설명한다. Access는 최대 5분의 stateless JWT이며 사용자 존재·권한은 MySQL에서 확인한다. Refresh는 Redis에 해시를 저장하고 회전·재사용 탐지·로그아웃 및 계정 변경 시 폐기를 처리한다. 상태 변경 요청은 CSRF 검사를 거친다.

기본 OAuth 등록은 Google이며 Kakao는 `kakao` 프로필을 추가할 때 활성화한다. 로그아웃·비밀번호 변경은 이미 발급한 Access를 즉시 폐기하지 않는다. Redis 장애 시 로그인·Refresh 회전·폐기는 실패하며, 기존 Access는 사용자 조회와 JWT 검증을 통과하면 사용할 수 있다.

- [로그인 흐름](login-flow.md)
- [Redis Refresh 회전 구현](refresh-rotation.md)
- [Access stateless 구현](stateless-access.md)
- [사용자 계정·입력 검증](user-account-validation.md)

[전체 문서](../README.md) · [프로젝트 README](../../README.md)
