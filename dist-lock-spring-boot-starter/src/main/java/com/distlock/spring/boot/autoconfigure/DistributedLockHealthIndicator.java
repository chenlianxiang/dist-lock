package com.distlock.spring.boot.autoconfigure;

import com.distlock.core.spi.LockStorageProvider;
import com.distlock.core.watchdog.WatchdogCoordinator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;

final class DistributedLockHealthIndicator implements HealthIndicator {

    private final Map<String, LockStorageProvider> providers;
    private final Map<String, WatchdogCoordinator> watchdogs;

    DistributedLockHealthIndicator(Map<String, LockStorageProvider> providers,
                                   Map<String, WatchdogCoordinator> watchdogs) {
        this.providers = providers;
        this.watchdogs = watchdogs;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, LockStorageProvider> entry : providers.entrySet()) {
                entry.getValue().validateConnectivity();
                details.put(entry.getKey(), "UP");
            }
            watchdogs.forEach((name, watchdog) ->
                    details.put(name + ".activeTasks", watchdog.activeTaskCount()));
            return Health.up().withDetails(details).build();
        } catch (RuntimeException failure) {
            return Health.down(failure).withDetails(details).build();
        }
    }
}
