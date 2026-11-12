package com.yu.retailops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "omniops.data-dir=${java.io.tmpdir}/omniops-context-${random.uuid}")
class AydRetailopsAgentApplicationTests {

    @Test
    void contextLoads() {
    }

}
