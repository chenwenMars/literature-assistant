-- 文献表
CREATE TABLE IF NOT EXISTS literature
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_name  VARCHAR(255) NOT NULL,
    file_path      VARCHAR(500) NOT NULL,
    file_size      BIGINT       NOT NULL,
    file_type      VARCHAR(10)  NOT NULL,
    content_length INT       DEFAULT 0,
    tags           VARCHAR(2000),
    description    VARCHAR(2000),
    reading_guide  CLOB,
    status         TINYINT   DEFAULT 1,
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT   DEFAULT 0
);

-- 增量升级：保留历史文献及报告，多次启动可重复执行。
ALTER TABLE literature ADD COLUMN IF NOT EXISTS source_document CLOB;
ALTER TABLE literature ADD COLUMN IF NOT EXISTS report_version VARCHAR(40);
ALTER TABLE literature ADD COLUMN IF NOT EXISTS error_message VARCHAR(2000);

CREATE TABLE IF NOT EXISTS literature_note (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    literature_id BIGINT NOT NULL REFERENCES literature(id),
    title VARCHAR(200) NOT NULL,
    category VARCHAR(20) NOT NULL,
    source_location VARCHAR(500) NOT NULL,
    excerpt CLOB NOT NULL,
    reflection CLOB NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_literature_note_document ON literature_note(literature_id);
