package com.distlock.core.context;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.UUID;

/**
 * 分布式环境下的锁持有者全局唯一标识生成器。
 * <p>
 * 格式形如: {@code <Host/IP>:<NodeUUID>:<PID>:<ThreadId>}
 * 保证在多节点、多实例、多线程高并发争抢中具备全局唯一性。
 */
public final class LockOwner {

    private static final String NODE_PREFIX;

    static {
        String host = "unknown-host";
        try {
            host = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {
        }
        String shortUuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long pid = ProcessHandle.current().pid();
        NODE_PREFIX = host + ":" + shortUuid + ":" + pid + ":";
    }

    private LockOwner() {
    }

    /**
     * 获取当前调用线程在全集群范围内的全局唯一标识。
     *
     * @return 全局唯一 Owner 字符串
     */
    public static String currentOwner() {
        return NODE_PREFIX + Thread.currentThread().getId();
    }

    /**
     * 为一次独立的加锁执行生成唯一持有令牌。
     * <p>
     * 令牌不能只绑定线程，否则同一线程的嵌套调用会共享 owner，内层释放时可能提前释放外层锁。
     */
    public static String newOwner() {
        return currentOwner() + ":" + UUID.randomUUID();
    }

    /**
     * 判断某个 owner 字符串是否属于当前 JVM 实例。
     */
    public static boolean isCurrentNode(String owner) {
        return owner != null && owner.startsWith(NODE_PREFIX);
    }
}
