package com.logimarui.platform.security.iiam;

import com.logimarui.platform.db.WriteDbConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import javax.sql.DataSource;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WriteDbSchemaProtectionTest {
    @Test void customEntityManagerUsesValidationByDefaultAndHonorsExplicitSetting() {
        var configuration=new WriteDbConfig();
        var source=mock(DataSource.class);
        assertEquals("validate",configuration.entityManagerFactory(source,new MockEnvironment()).getJpaPropertyMap().get("hibernate.hbm2ddl.auto"));
        assertEquals("none",configuration.entityManagerFactory(source,new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto","none")).getJpaPropertyMap().get("hibernate.hbm2ddl.auto"));
    }
}
