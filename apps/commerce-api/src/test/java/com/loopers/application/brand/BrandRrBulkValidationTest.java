package com.loopers.application.brand;

import org.springframework.boot.test.context.SpringBootTest;

/** Validate the unhinted RR bulk candidate using the same contract and lock-race cases. */
@SpringBootTest(properties = {"spring.jpa.show-sql=false", "candidate.strategy=rr-plain"})
class BrandRrBulkValidationTest extends BrandRcBulkValidationTest {
    @Override
    protected boolean allowsPlanDependentUnrelatedWait() {
        return true;
    }
}
