package com.distlock.provider.db;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MySqlLockStorageProviderIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40")
            .withDatabaseName("dist_lock")
            .withUsername("dist_lock")
            .withPassword("dist_lock");

    private static DataSource dataSource;
    private static DatabaseLockStorageProvider provider;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource configured = new DriverManagerDataSource();
        configured.setDriverClassName(MYSQL.getDriverClassName());
        configured.setUrl(MYSQL.getJdbcUrl());
        configured.setUsername(MYSQL.getUsername());
        configured.setPassword(MYSQL.getPassword());
        dataSource = configured;
        new ResourceDatabasePopulator(new ClassPathResource("schema/schema-mysql.sql")).execute(dataSource);
        provider = new DatabaseLockStorageProvider(dataSource, Duration.ofSeconds(2));
    }

    @Test
    void fencingTokenIncreasesOnEveryNewOwnership() {
        var first = provider.tryAcquire("mysql-fence", "owner-1", 5_000);
        assertThat(first.acquired()).isTrue();
        assertThat(provider.release("mysql-fence", "owner-1")).isTrue();

        var second = provider.tryAcquire("mysql-fence", "owner-2", 5_000);
        assertThat(second.acquired()).isTrue();
        assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
        provider.release("mysql-fence", "owner-2");
    }

    @Test
    void acquisitionCommitsIndependentlyFromOuterBusinessRollback() {
        TransactionTemplate businessTransaction =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        businessTransaction.executeWithoutResult(status -> {
            assertThat(provider.tryAcquire("requires-new", "owner", 5_000).acquired()).isTrue();
            status.setRollbackOnly();
        });

        Integer rows = new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM dist_lock WHERE lock_key = ? AND owner = ?",
                Integer.class, "requires-new", "owner");
        assertThat(rows).isEqualTo(1);
        provider.release("requires-new", "owner");
    }

    @Test
    void onlyOneProviderWinsConcurrentAcquisition() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> attempts = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(index -> (Callable<Boolean>) () ->
                            new DatabaseLockStorageProvider(dataSource)
                                    .tryAcquire("mysql-race", "owner-" + index, 5_000).acquired())
                    .toList();
            long winners = executor.invokeAll(attempts).stream().filter(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).count();
            assertThat(winners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
