# 사용자 계정·입력 검증

## 요청과 서비스 경계

회원가입·로그인·비밀번호 변경·닉네임 변경은 HTTP `@Valid`와 `UserService`의 `@Validated`·`@NotNull @Valid`를 함께 적용한다. HTTP를 거치지 않는 다른 Spring 빈의 서비스 호출도 같은 DTO 제약을 검사한다. 직접 `new UserService(...)`로 생성하거나 같은 객체 내부에서 호출하면 Spring 프록시 검증을 우회하므로 실제 애플리케이션에서는 주입된 빈을 사용한다.

| 입력 | 제약 |
| --- | --- |
| 이메일 | `@NotBlank`, `@Email`, 최대 254자 |
| 가입·새 비밀번호 | 공백만으로 구성 불가, 8~72자, UTF-8 최대 72바이트 |
| 로그인 비밀번호 | 공백만으로 구성 불가, 1~72자, UTF-8 최대 72바이트; 가입 최소 길이는 재검사하지 않음 |
| 닉네임 | 공백만으로 구성 불가, 2~50자; 별도 조합 규칙·중복 제한 없음 |

비밀번호는 trim·정규화하지 않는다. `@Size`의 문자열 길이와 별개로 `@Utf8ByteLength`가 BCrypt 입력의 72바이트 상한을 검사한다. 한글 24자는 72바이트지만 25자는 거절한다. 요청 길이는 Java 문자열 길이 기준이다.

검증 실패·누락/잘못된 JSON은 `400 INVALID_REQUEST`와 일반 메시지를 반환한다. 거절한 값·비밀번호·예외 상세는 이 응답과 검증 핸들러 로그에 포함하지 않는다. 이메일 중복·비밀번호 불일치와 기존 인증·CSRF 계약은 유지한다.

## 계정 모델

이메일은 저장·조회 시 공백 제거와 소문자 정규화를 적용한다. 로컬 계정은 BCrypt `password_hash`를 가지며 `provider_subject`는 없다. Google·Kakao 계정은 비밀번호 없이 제공자의 변경되지 않는 subject로 식별한다. 이메일·provider 조합과 provider·subject 조합은 유일하다. 이메일이 같아도 자동으로 계정을 연결하지 않는다.

이메일·닉네임·provider는 필수이며 길이는 각각 254·50·16자이다. 엔티티 저장 경계도 로컬/OAuth 속성 조합과 필수값을 검사한다. OAuth 입력은 공개 DTO와 별도로 서버가 검증하고, 표시 이름이 없으면 대체값을 사용한다.

## 초기 개발 DB 정책

사용 가능한 기존 데이터가 없다는 결정에 따라 이번 변경은 새 스키마로 재생성하는 초기 개발 전용 정책을 따른다. 데이터 보존·백필·구형 계정 자동 연결은 제공하지 않는다. 실제 재생성 대상과 실행 결과는 [MySQL 실행 가이드](mysql-setup.md)에 기록한다. 문서 작성이나 일반 테스트가 기존 DB를 자동 삭제하지 않는다.

새 데이터가 쌓인 뒤에는 적용된 migration을 수정하거나 Flyway 이력을 지우지 않고 후속 migration을 추가한다. Hibernate 자동 스키마 수정 대신 Flyway와 `ddl-auto=validate`를 유지한다. MySQL 초기화와 Redis Refresh 정리는 별도 저장소 작업이며 토큰·비밀값을 로그에 남기지 않는다.

## 검증

`UserValidationTest`는 실제 MVC 바인딩·검증과 잘못된 요청의 서비스 미호출을 확인한다. `Utf8ByteLengthValidatorTest`는 ASCII·한글·이모지의 바이트 경계를, `UserServiceMethodValidationTest`는 실제 Spring 프록시에서 null·잘못된 DTO가 저장소·암호화·토큰 호출 전에 거절되는지 확인한다. 이 테스트들은 실제 OAuth·LLM·외부 인증 저장소를 호출하지 않는다.

```powershell
.\gradlew.bat test --tests '*UserValidationTest' --tests '*Utf8ByteLengthValidatorTest' --tests '*UserServiceMethodValidationTest'
```
