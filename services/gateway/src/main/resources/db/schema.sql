-- DiffGuard 数据库初始化脚本
-- 在 Docker 启动时自动执行

CREATE DATABASE IF NOT EXISTS diffguard;
USE diffguard;

-- Review 任务表
CREATE TABLE IF NOT EXISTS review_task (
    id VARCHAR(36) PRIMARY KEY,
    repo_name VARCHAR(255),
    pr_number INT,
    mode VARCHAR(20) NOT NULL DEFAULT 'SIMPLE',
    status ENUM('PENDING','RUNNING','COMPLETED','FAILED') NOT NULL DEFAULT 'PENDING',
    diff_file_count INT NOT NULL DEFAULT 0,
    diff_summary TEXT,
    strategy_json TEXT,
    error_message TEXT,
    created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),
    started_at TIMESTAMP(3) NULL,
    completed_at TIMESTAMP(3) NULL,
    INDEX idx_status (status),
    INDEX idx_repo_pr (repo_name, pr_number),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Review 结果表
CREATE TABLE IF NOT EXISTS review_result (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    severity VARCHAR(10) NOT NULL DEFAULT 'INFO',
    file_path VARCHAR(500) NOT NULL,
    line_number INT,
    issue_type VARCHAR(100) NOT NULL,
    message TEXT NOT NULL,
    suggestion TEXT,
    agent_type VARCHAR(50),
    confidence FLOAT DEFAULT 1.0,
    source ENUM('STATIC_RULE','LLM_AGENT') NOT NULL DEFAULT 'LLM_AGENT',
    created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_task (task_id),
    INDEX idx_severity (severity),
    INDEX idx_type (issue_type),
    INDEX idx_file (file_path(255)),
    FOREIGN KEY (task_id) REFERENCES review_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Review 统计表（聚合统计，定时任务更新）
CREATE TABLE IF NOT EXISTS review_stats (
    id INT AUTO_INCREMENT PRIMARY KEY,
    repo_name VARCHAR(255) NOT NULL,
    stat_date DATE NOT NULL,
    total_reviews INT NOT NULL DEFAULT 0,
    total_issues INT NOT NULL DEFAULT 0,
    critical_count INT NOT NULL DEFAULT 0,
    warning_count INT NOT NULL DEFAULT 0,
    avg_duration_ms INT NOT NULL DEFAULT 0,
    avg_tokens_used INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_repo_date (repo_name, stat_date),
    INDEX idx_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
