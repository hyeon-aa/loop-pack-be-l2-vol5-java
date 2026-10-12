package com.loopers.application.brand;

import org.springframework.boot.test.context.SpringBootTest;

/** Run the same contract and lock-race cases with RR and an explicit existing-index hint. */
@SpringBootTest(properties = {"spring.jpa.show-sql=false", "candidate.strategy=rr-hint"})
class BrandRrHintBulkValidationTest extends BrandRcBulkValidationTest {}
