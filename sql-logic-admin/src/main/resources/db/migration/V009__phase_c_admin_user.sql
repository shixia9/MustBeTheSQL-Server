-- V009: Phase C — system admin user table for admin dashboard access control.
CREATE TABLE IF NOT EXISTS admin_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'ADMIN' COMMENT 'SUPER_ADMIN or ADMIN',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=active, 0=disabled',
    created_by BIGINT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_admin_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed the initial super admin (user_id=1).
INSERT IGNORE INTO admin_user (user_id, role, status, created_by, create_time)
VALUES (1, 'SUPER_ADMIN', 1, NULL, NOW());
