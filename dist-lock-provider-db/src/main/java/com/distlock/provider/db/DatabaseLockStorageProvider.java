package com.distlock.provider.db;

import com.distlock.core.spi.LockStorageProvider;
import com.distlock.core.exception.LockStorageException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.Objects;

/**
 * 基于关系型数据库（MySQL、PostgreSQL、H2等）CAS UPDATE 租约机制的分布式锁存储提供者。
 * <p>
 * 特性：
 * 1. 采用 ANSI-SQL 标准的 CAS UPDATE 租约模型，无需额外中间件即可支持跨多集群排他锁；
 * 2. 避免长时间持有物理数据库连接或长事务，单次原子 SQL 执行后即时归还连接池；
 * 3. 使用数据库全局统一时间戳消除各微服务服务器之间的时钟漂移（Clock Skew）；
 * 4. 自动处理初次建行与过期记录的抢占。
 */
public class DatabaseLockStorageProvider implements LockStorageProvider {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_CAS_UPDATE =
            "UPDATE dist_lock SET owner = ?, expire_time = ?, version = version + 1 " +
            "WHERE lock_key = ? AND expire_time < ?";

    private static final String SQL_INSERT =
            "INSERT INTO dist_lock (lock_key, owner, expire_time, version) VALUES (?, ?, ?, 1)";

    private static final String SQL_RELEASE =
            "UPDATE dist_lock SET owner = '', expire_time = 0, version = version + 1 " +
            "WHERE lock_key = ? AND owner = ?";

    private static final String SQL_RENEW =
            "UPDATE dist_lock SET expire_time = ?, version = version + 1 " +
            "WHERE lock_key = ? AND owner = ? AND expire_time >= ?";

    private static final String SQL_STORAGE_TIME = "SELECT CURRENT_TIMESTAMP";

    public DatabaseLockStorageProvider(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    public DatabaseLockStorageProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public boolean tryAcquire(String lockKey, String owner, long leaseMillis) {
        long now = getStorageTimeMillis();
        long expireAt = now + leaseMillis;

        // 1. 先尝试通过 CAS UPDATE 抢占过期租约（适用于表中已有记录的热锁场景）
        try {
            int updated = jdbcTemplate.update(SQL_CAS_UPDATE, owner, expireAt, lockKey, now);
            if (updated > 0) {
                return true;
            }

            // 2. 若更新行数为 0，可能是该 lock_key 首次出现，尚未入库，尝试原子插入
            int inserted = jdbcTemplate.update(SQL_INSERT, lockKey, owner, expireAt);
            if (inserted > 0) {
                return true;
            }
        } catch (DuplicateKeyException ex) {
            // 3. 并发争抢时主键冲突说明其他节点已插入该 key，立即重试一次 CAS UPDATE
            try {
                return jdbcTemplate.update(SQL_CAS_UPDATE, owner, expireAt, lockKey, now) > 0;
            } catch (DataAccessException retryFailure) {
                throw new LockStorageException("acquire", lockKey, retryFailure);
            }
        } catch (DataAccessException ex) {
            throw new LockStorageException("acquire", lockKey, ex);
        }

        return false;
    }

    @Override
    public boolean release(String lockKey, String owner) {
        try {
            return jdbcTemplate.update(SQL_RELEASE, lockKey, owner) > 0;
        } catch (DataAccessException ex) {
            throw new LockStorageException("release", lockKey, ex);
        }
    }

    @Override
    public boolean renew(String lockKey, String owner, long leaseMillis) {
        long now = getStorageTimeMillis();
        long newExpireAt = now + leaseMillis;

        try {
            return jdbcTemplate.update(SQL_RENEW, newExpireAt, lockKey, owner, now) > 0;
        } catch (DataAccessException ex) {
            throw new LockStorageException("renew", lockKey, ex);
        }
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
}
