// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.it;

import com.cominotti.k8sbatch.crud.it.config.CrudMysqlContainersConfig;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

/**
 * Base class for JPA repository slice tests ({@code @DataJpaTest}).
 *
 * <p>Uses a real MySQL Testcontainer (not H2) to validate Liquibase-managed schema and
 * MySQL-specific behavior. Each test method runs in a transaction that rolls back by default.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CrudMysqlContainersConfig.class)
@ActiveProfiles("crud-test")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public abstract class AbstractCrudSliceTest {
}
