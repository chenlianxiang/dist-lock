package com.distlock.provider.db;

import com.distlock.core.spi.LockAcquisition;
import com.distlock.core.spi.LockStorageProvider;
import com.distlock.core.exception.LockStorageException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 基于关系型数据库（MySQL、PostgreSQL、H2等）CAS UPDATE 租约机制的分布式锁存储提供者。
 * <p>
 * 特性：
 * 1. 采用 ANSI-SQL 标准的 CAS UPDATE 租约模型，无需额外中间件即可支持跨多集群排他锁；
 * 2. 避免长时间持有物理数据库连接或长事务，单次原子 SQL 执行后即时归还连接池；
 * 3. 使用数据库全局统一时间戳消除各微服务服务器之间的时钟漂移（Clock Skew）；
 * 4. 自动处理初次建行与过期记录的抢占。
 */
public final class DatabaseLockStorageProvider implements LockStorageProvider {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    private static final String SQL_CAS_UPDATE =
            "UPDATE dist_lock SET owner = ?, expire_time = ?, version = version + 1 " +
            "WHERE lock_key = ? AND expire_time < ?";

    private static final String SQL_INSERT =
            "INSERT INTO dist_lock (lock_key, owner, expire_time, version) VALUES (?, ?, ?, 1)";

    private static final String SQL_RELEASE =
            "UPDATE dist_lock SET owner = '', expire_time = 0 " +
            "WHERE lock_key = ? AND owner = ?";

    private static final String SQL_RENEW =
            "UPDATE dist_lock SET expire_time = ? " +
            "WHERE lock_key = ? AND owner = ? AND expire_time >= ?";

    private static final String SQL_STORAGE_TIME = "SELECT CURRENT_TIMESTAMP";
    private static final String SQL_FENCING_TOKEN =
            "SELECT version FROM dist_lock WHERE lock_key = ? AND owner = ?";
    private static final String SQL_FENCE_ROW_COUNT =
            "SELECT COUNT(*) FROM dist_lock_fence WHERE lock_key = ?";
    private static final String SQL_INSERT_FENCE_ROW =
            "INSERT INTO dist_lock_fence (lock_key, fencing_token) VALUES (?, 0)";

    public DatabaseLockStorageProvider(DataSource dataSource) {
        this(dataSource, Duration.ofSeconds(3));
    }

    public DatabaseLockStorageProvider(DataSource dataSource, Duration operationTimeout) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        Objects.requireNonNull(operationTimeout, "operationTimeout must not be null");
        long timeoutSeconds = Math.max(1, operationTimeout.toSeconds());
        if (timeoutSeconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("operationTimeout is too large");
        }
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setQueryTimeout((int) timeoutSeconds);
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setTimeout((int) timeoutSeconds);
    }

    @Override
    public LockAcquisition tryAcquire(String lockKey, String owner, long leaseMillis) {
        return inNewTransaction(() -> doTryAcquire(lockKey, owner, leaseMillis));
    }

    private LockAcquisition doTryAcquire(String lockKey, String owner, long leaseMillis) {
        long now = getStorageTimeMillis();
        long expireAt = Math.addExact(now, leaseMillis);

        // 1. 先尝试通过 CAS UPDATE 抢占过期租约（适用于表中已有记录的热锁场景）
        try {
            int updated = jdbcTemplate.update(SQL_CAS_UPDATE, owner, expireAt, lockKey, now);
            if (updated > 0) {
                return acquired(lockKey, owner);
            }

            // 2. 若更新行数为 0，可能是该 lock_key 首次出现，尚未入库，尝试原子插入
            int inserted = jdbcTemplate.update(SQL_INSERT, lockKey, owner, expireAt);
            if (inserted > 0) {
                ensureFenceRow(lockKey);
                return LockAcquisition.acquired(1);
            }
        } catch (DuplicateKeyException ex) {
            // 3. 并发争抢时主键冲突说明其他节点已插入该 key，立即重试一次 CAS UPDATE
            try {
                if (jdbcTemplate.update(SQL_CAS_UPDATE, owner, expireAt, lockKey, now) > 0) {
                    return acquired(lockKey, owner);
                }
                return LockAcquisition.contended();
            } catch (DataAccessException retryFailure) {
                throw new LockStorageException("acquire", lockKey, retryFailure);
            }
        } catch (DataAccessException ex) {
            throw new LockStorageException("acquire", lockKey, ex);
        }

        return LockAcquisition.contended();
    }

    @Override
    public boolean release(String lockKey, String owner) {
        return inNewTransaction(() -> {
            try {
                return jdbcTemplate.update(SQL_RELEASE, lockKey, owner) > 0;
            } catch (DataAccessException ex) {
                throw new LockStorageException("release", lockKey, ex);
            }
        });
    }

    @Override
    public boolean renew(String lockKey, String owner, long leaseMillis) {
        return inNewTransaction(() -> {
            long now = getStorageTimeMillis();
            long newExpireAt = Math.addExact(now, leaseMillis);
            try {
                return jdbcTemplate.update(SQL_RENEW, newExpireAt, lockKey, owner, now) > 0;
            } catch (DataAccessException ex) {
                throw new LockStorageException("renew", lockKey, ex);
            }
        });
    }

    @Override
    public long getStorageTimeMillis() {
        try {
            Timestamp ts = jdbcTemplate.queryForObject(SQL_STORAGE_TIME, Timestamp.class);
            if (ts != null) {
                return ts.getTime();
            }
        } catch (DataAccessException e) {
            throw new LockStorageException("time", "<storage-clock>", e);
        }
        throw new LockStorageException("time", "<storage-clock>",
                new IllegalStateException("Database returned a null timestamp"));
    }

    private LockAcquisition acquired(String lockKey, String owner) {
        ensureFenceRow(lockKey);
        Long token = jdbcTemplate.queryForObject(SQL_FENCING_TOKEN, Long.class, lockKey, owner);
        if (token == null || token <= 0) {
            throw new LockStorageException("acquire", lockKey,
                    new IllegalStateException("Acquired row has no fencing token"));
        }
        return LockAcquisition.acquired(token);
    }

    private void ensureFenceRow(String lockKey) {
        try {
            Integer rows = jdbcTemplate.queryForObject(SQL_FENCE_ROW_COUNT, Integer.class, lockKey);
            if (rows == null || rows == 0) {
                jdbcTemplate.update(SQL_INSERT_FENCE_ROW, lockKey);
            }
        } catch (DataAccessException exception) {
            throw new LockStorageException("initialize-fence", lockKey, exception);
        }
    }

    private <T> T inNewTransaction(Supplier<T> action) {
        T result = transactionTemplate.execute(status -> action.get());
        if (result == null) {
            throw new IllegalStateException("Lock transaction returned null");
        }
        return result;
    }
}
