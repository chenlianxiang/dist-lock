package com.distlock.provider.db;

import com.distlock.core.api.LockHandle;
import com.distlock.core.api.LockLease;
import com.distlock.core.api.DefaultDistributedLocker;
import com.distlock.core.api.LockConfig;
import com.distlock.core.api.LockStrategy;
import com.distlock.core.exception.FencingRejectedException;
import com.distlock.core.metrics.LockMetrics;
import com.distlock.core.watchdog.WatchdogCoordinator;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE fenced_business (business_key VARCHAR(64) PRIMARY KEY, value_text VARCHAR(64))");
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

    @Test
    void plainCallAutomaticallyFencesBusinessInTheSameDatabase() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        WatchdogCoordinator coordinator = new WatchdogCoordinator(
                provider, 2, LockStrategy.DATABASE.name(), LockMetrics.NOOP);
        DefaultDistributedLocker locker = new DefaultDistributedLocker(
                provider,
                coordinator,
                LockConfig.defaultConfig(),
                LockStrategy.DATABASE,
                LockMetrics.NOOP,
                guard()
        );
        try {
            assertThat(locker.lock("automatic-call", Function.identity()).call(() ->
                    jdbc.update("INSERT INTO fenced_business (business_key, value_text) VALUES (?, ?)",
                            "automatic-call", "committed"))).isEqualTo(1);

            Long accepted = jdbc.queryForObject(
                    "SELECT fencing_token FROM dist_lock_fence WHERE lock_key = ?",
                    Long.class, "dist-lock:v1:java.lang.String:java.lang.String:automatic-call");
            assertThat(accepted).isPositive();
        } finally {
            coordinator.shutdown();
        }
    }

    @Test
    void jdbcGuardRejectsStaleTokenBeforeBusinessRuns() {
        JdbcFencingGuard guard = guard();
        AtomicBoolean staleActionCalled = new AtomicBoolean();
        seedFenceRow("automatic-stale");

        guard.execute(handle("automatic-stale", 2), () -> "new-owner");

        assertThatThrownBy(() -> guard.execute(handle("automatic-stale", 1), () -> {
            staleActionCalled.set(true);
            return "old-owner";
        })).isInstanceOf(FencingRejectedException.class);
        assertThat(staleActionCalled).isFalse();
    }

    @Test
    void fencingClaimAndBusinessWriteRollBackTogether() {
        JdbcFencingGuard guard = guard();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedFenceRow("automatic-rollback");

        assertThatThrownBy(() -> guard.execute(handle("automatic-rollback", 2), () -> {
            jdbc.update("INSERT INTO fenced_business (business_key, value_text) VALUES (?, ?)",
                    "rollback", "must-not-commit");
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        Integer businessRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fenced_business WHERE business_key = 'rollback'", Integer.class);
        Long acceptedToken = jdbc.queryForObject(
                "SELECT fencing_token FROM dist_lock_fence WHERE lock_key = ?",
                Long.class, "automatic-rollback");
        assertThat(businessRows).isZero();
        assertThat(acceptedToken).isZero();

        assertThat(guard.execute(handle("automatic-rollback", 1), () -> "accepted"))
                .isEqualTo("accepted");
    }

    @Test
    void newerTokenCommitsAfterOlderInFlightTransaction() throws Exception {
        JdbcFencingGuard guard = guard();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedFenceRow("automatic-order");
        CountDownLatch oldTransactionStarted = new CountDownLatch(1);
        CountDownLatch allowOldCommit = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var oldHolder = executor.submit(() -> guard.execute(handle("automatic-order", 1), () -> {
                jdbc.update("INSERT INTO fenced_business (business_key, value_text) VALUES (?, ?)",
                        "ordered", "old");
                oldTransactionStarted.countDown();
                try {
                    assertThat(allowOldCommit.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return "old";
            }));

            assertThat(oldTransactionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var newHolder = executor.submit(() -> guard.execute(handle("automatic-order", 2), () -> {
                jdbc.update("UPDATE fenced_business SET value_text = ? WHERE business_key = ?",
                        "new", "ordered");
                return "new";
            }));

            Thread.sleep(100);
            assertThat(newHolder.isDone()).isFalse();
            allowOldCommit.countDown();
            assertThat(oldHolder.get(5, TimeUnit.SECONDS)).isEqualTo("old");
            assertThat(newHolder.get(5, TimeUnit.SECONDS)).isEqualTo("new");
            assertThat(jdbc.queryForObject(
                    "SELECT value_text FROM fenced_business WHERE business_key = 'ordered'", String.class))
                    .isEqualTo("new");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void batchClaimRollsBackWhenAnyTokenIsStale() {
        JdbcFencingGuard guard = guard();
        seedFenceRow("batch-a");
        seedFenceRow("batch-b");
        guard.execute(handle("batch-b", 5), () -> "seed");
        AtomicBoolean actionCalled = new AtomicBoolean();
        LockHandle batch = new LockHandle("batch-owner", List.of(
                new LockLease("batch-a", 3),
                new LockLease("batch-b", 4)
        ));

        assertThatThrownBy(() -> guard.execute(batch, () -> {
            actionCalled.set(true);
            return "unexpected";
        })).isInstanceOf(FencingRejectedException.class);

        assertThat(actionCalled).isFalse();
        Long firstToken = new JdbcTemplate(dataSource).queryForObject(
                "SELECT fencing_token FROM dist_lock_fence WHERE lock_key = 'batch-a'", Long.class);
        assertThat(firstToken).isZero();
    }

    private static JdbcFencingGuard guard() {
        return new JdbcFencingGuard(dataSource,
                new DataSourceTransactionManager(dataSource), Duration.ofSeconds(5));
    }

    private static LockHandle handle(String lockKey, long token) {
        return new LockHandle("test-owner", List.of(new LockLease(lockKey, token)));
    }

    private static void seedFenceRow(String lockKey) {
        new JdbcTemplate(dataSource).update(
                "INSERT INTO dist_lock_fence (lock_key, fencing_token) VALUES (?, 0)", lockKey);
    }
}
