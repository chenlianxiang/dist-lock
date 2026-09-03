package com.distlock.provider.db;

import com.distlock.core.spi.LockStorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
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

    private static final Logger log = LoggerFactory.getLogger(DatabaseLockStorageProvider.class);

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_CAS_UPDATE =
            "UPDATE dist_lock SET owner = ?, expire_time = ?, version = version + 1 " +
            "WHERE lock_key = ? AND (expire_time < ? OR owner = ?)";

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

        // 1. 先尝试通过 CAS UPDATE 抢占或重入（适用于表中已有记录的热锁场景）
        int updated = jdbcTemplate.update(SQL_CAS_UPDATE, owner, expireAt, lockKey, now, owner);
        if (updated > 0) {
            return true;
        }

        // 2. 若更新行数为 0，可能是该 lock_key 首次出现，尚未入库，尝试原子插入
        try {
            int inserted = jdbcTemplate.update(SQL_INSERT, lockKey, owner, expireAt);
            if (inserted > 0) {
                return true;
            }
        } catch (DataIntegrityViolationException ex) {
            // 3. 并发争抢时主键冲突说明其他节点已插入该 key，立即重试一次 CAS UPDATE
            updated = jdbcTemplate.update(SQL_CAS_UPDATE, owner, expireAt, lockKey, now, owner);
            return updated > 0;
        } catch (Exception ex) {
            log.warn("Unexpected exception during insert lock [{}]", lockKey, ex);
        }

        return false;
    }

    @Override
    public boolean release(String lockKey, String owner) {
        int updated = jdbcTemplate.update(SQL_RELEASE, lockKey, owner);
        return updated > 0;
    }

    @Override
    public boolean renew(String lockKey, String owner, long leaseMillis) {
        long now = getStorageTimeMillis();
        long newExpireAt = now + leaseMillis;

        int updated = jdbcTemplate.update(SQL_RENEW, newExpireAt, lockKey, owner, now);
        return updated > 0;
    }

    @Override
    public long getStorageTimeMillis() {
        try {
            Timestamp ts = jdbcTemplate.queryForObject(SQL_STORAGE_TIME, Timestamp.class);
            if (ts != null) {
                return ts.getTime();
            }
        } catch (Exception e) {
            log.debug("Failed to fetch database timestamp, fallback to System.currentTimeMillis()", e);
        }
        return System.currentTimeMillis();
    }
}
