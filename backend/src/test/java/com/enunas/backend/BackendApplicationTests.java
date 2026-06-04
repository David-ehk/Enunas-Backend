package com.enunas.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Test profile: throwaway Postgres (Testcontainers) + mock payments, so the smoke test is
// self-contained and does not depend on production env vars (DB_PASSWORD, JWT_SECRET, …).
@SpringBootTest
@ActiveProfiles({"test", "mock-payments"})
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
