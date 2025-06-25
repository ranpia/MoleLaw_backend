package com.MoleLaw_backend;

import com.MoleLaw_backend.service.law.ExtractKeyword;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest(properties = "spring.profiles.active=test")
class LawMateBackendApplicationTests {

	@TestConfiguration
	static class TestConfig {
		@Bean
		public ExtractKeyword extractKeyword() {
			return Mockito.mock(ExtractKeyword.class);  // 또는 다른 Mock 구현체
		}
	}

	@Autowired
	private ExtractKeyword extractKeyword;

	@Test
	void contextLoads() {
		// 테스트 가능
	}
}

