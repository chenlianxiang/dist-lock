package com.distlock.provider.db;

import com.distlock.core.api.LockHandle;
import com.distlock.core.api.LockLease;
import com.distlock.core.exception.FencingRejectedException;
import com.distlock.core.exception.LockStorageException;
import com.distlock.core.fencing.FencingGuard;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 使用业务数据库事务自动实施 fencing。
 * token 声明与业务闭包共享同一事务，从而让较新的持有者在提交顺序上超越旧持有者。
 */
public final class JdbcFencingGuard implements FencingGuard {

    private static final String SQL_CLAIM =
            "UPDATE dist_lock_fence SET fencing_token = ? "
                    + "WHERE lock_key = ? AND fencing_token < ?";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate businessTransaction;

    public JdbcFencingGuard(DataSource dataSource,
                            PlatformTransactionManager transactionManager,
                            Duration transactionTimeout) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        Objects.requireNonNull(transactionTimeout, "transactionTimeout must not be null");
        long timeoutMillis = transactionTimeout.toMillis();
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("transactionTimeout must be greater than 0");
        }
        long timeoutSeconds = Math.max(1, Math.addExact(timeoutMillis, 999) / 1000);
        if (timeoutSeconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("transactionTimeout is too large");
        }

        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setQueryTimeout((int) timeoutSeconds);
        this.businessTransaction = transactionTemplate(
                transactionManager, TransactionDefinition.PROPAGATION_REQUIRED, (int) timeoutSeconds);
    }

    @Override
    public <R> R execute(LockHandle handle, Supplier<R> action) {
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(action, "action must not be null");
        List<LockLease> leases = handle.leases().stream()
                .sorted(Comparator.comparing(LockLease::lockKey))
                .toList();

        return businessTransaction.execute(status -> {
            for (LockLease lease : leases) {
                claim(lease);
            }
            return action.get();
        });
    }

    private void claim(LockLease lease) {
        try {
            int updated = jdbcTemplate.update(SQL_CLAIM,
                    lease.fencingToken(), lease.lockKey(), lease.fencingToken());
            if (updated == 0) {
                throw new FencingRejectedException(lease.lockKey(), lease.fencingToken());
            }
        } catch (FencingRejectedException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new LockStorageException("claim-fence", lease.lockKey(), exception);
        }
    }

    private static TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager,
                                                           int propagation,
                                                           int timeoutSeconds) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(propagation);
        template.setTimeout(timeoutSeconds);
        return template;
    }
}
