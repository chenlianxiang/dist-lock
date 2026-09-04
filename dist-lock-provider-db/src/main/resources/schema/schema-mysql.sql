CREATE TABLE IF NOT EXISTS `dist_lock` (
  `lock_key` VARCHAR(255) NOT NULL COMMENT '锁唯一资源标识',
  `owner` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '锁持有者唯一标识(Node:PID:ThreadId)',
  `expire_time` BIGINT NOT NULL DEFAULT 0 COMMENT '绝对过期时间戳(毫秒)',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`lock_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用分布式锁租约表';

CREATE TABLE IF NOT EXISTS `dist_lock_fence` (
  `lock_key` VARCHAR(255) NOT NULL COMMENT '完整锁资源标识',
  `fencing_token` BIGINT NOT NULL DEFAULT 0 COMMENT '已进入业务提交边界的最大 token',
  PRIMARY KEY (`lock_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分布式锁业务写入 fencing 表';
