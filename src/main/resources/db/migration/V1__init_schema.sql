-- V1 初始建表脚本(Flyway 起点)
-- 由当前 JPA 实体映射而来:PostgreSQL。
-- 建表顺序按外键依赖排列:被引用的表在前。

-- ============================================================
-- 一次性清理:丢弃 ddl-auto 建的旧表,让本脚本成为结构的唯一真相。
-- 只删业务表,不用 DROP SCHEMA —— flyway_schema_history 也在 public 下,
-- 整个 schema 一起删会连 Flyway 自己的历史表都毁掉。CASCADE 处理外键依赖。
-- (数据可丢弃前提下才这样做;V1 只跑一次,故此清理也只发生一次。)
-- ============================================================
DROP TABLE IF EXISTS
    work_follows,
    applications,
    tickets,
    contributions,
    work_tags,
    work_authors,
    works,
    tags,
    users
CASCADE;

-- ============================================================
-- users
-- ============================================================
CREATE TABLE users (
    id           UUID         NOT NULL,
    email        VARCHAR(255) NOT NULL,
    password     VARCHAR(255) NOT NULL,
    username     VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    role         VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_username UNIQUE (username)
);

-- ============================================================
-- tags
-- ============================================================
CREATE TABLE tags (
    id   UUID         NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_tags PRIMARY KEY (id),
    CONSTRAINT uk_tags_name UNIQUE (name)
);

-- ============================================================
-- works
-- ============================================================
CREATE TABLE works (
    id                  UUID         NOT NULL,
    title               VARCHAR(255) NOT NULL,
    preface             TEXT         NOT NULL,
    completed           BOOLEAN      NOT NULL DEFAULT FALSE,
    work_type           VARCHAR(255) NOT NULL,
    turn_style          VARCHAR(255) NOT NULL,
    writing_mode        VARCHAR(255) NOT NULL,
    visibility          VARCHAR(255) NOT NULL,
    access_code         VARCHAR(255),
    max_rounds          INTEGER,
    max_words           INTEGER      NOT NULL DEFAULT 1000,
    max_paragraphs      INTEGER      NOT NULL DEFAULT 1,
    ticket_ttl_seconds  BIGINT       NOT NULL DEFAULT 3600,
    max_participants    INTEGER,
    created_author_id   UUID         NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    updated_at          TIMESTAMP(6) NOT NULL,
    version             BIGINT,
    CONSTRAINT pk_works PRIMARY KEY (id),
    CONSTRAINT fk_works_created_author FOREIGN KEY (created_author_id) REFERENCES users (id)
);

-- ============================================================
-- work_authors (Work.authors, M2M)
-- ============================================================
CREATE TABLE work_authors (
    work_id UUID NOT NULL,
    user_id UUID NOT NULL,
    CONSTRAINT pk_work_authors PRIMARY KEY (work_id, user_id),
    CONSTRAINT fk_work_authors_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_work_authors_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- ============================================================
-- work_tags (Work.tags, M2M)
-- ============================================================
CREATE TABLE work_tags (
    work_id UUID NOT NULL,
    tag_id  UUID NOT NULL,
    CONSTRAINT pk_work_tags PRIMARY KEY (work_id, tag_id),
    CONSTRAINT fk_work_tags_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_work_tags_tag FOREIGN KEY (tag_id) REFERENCES tags (id)
);

-- ============================================================
-- contributions
-- sequence_number 可空:草稿(draft=true)阶段还没有顺序号,提交时才分配。
-- ============================================================
CREATE TABLE contributions (
    id              UUID         NOT NULL,
    work_id         UUID         NOT NULL,
    author_id       UUID         NOT NULL,
    title           VARCHAR(255),
    content         TEXT         NOT NULL,
    sequence_number INTEGER,
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted_by_id   UUID,
    draft           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_contributions PRIMARY KEY (id),
    CONSTRAINT uk_contributions_work_sequence UNIQUE (work_id, sequence_number),
    CONSTRAINT fk_contributions_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_contributions_author FOREIGN KEY (author_id) REFERENCES users (id),
    CONSTRAINT fk_contributions_deleted_by FOREIGN KEY (deleted_by_id) REFERENCES users (id)
);

-- ============================================================
-- tickets
-- ============================================================
CREATE TABLE tickets (
    id              UUID         NOT NULL,
    work_id         UUID         NOT NULL,
    holder_id       UUID         NOT NULL,
    ticket_number   BIGINT       NOT NULL,
    status          VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    activated_at    TIMESTAMP(6),
    expires_at      TIMESTAMP(6),
    contribution_id UUID,
    version         BIGINT,
    CONSTRAINT pk_tickets PRIMARY KEY (id),
    CONSTRAINT uk_tickets_work_number UNIQUE (work_id, ticket_number),
    CONSTRAINT uk_tickets_contribution UNIQUE (contribution_id),
    CONSTRAINT fk_tickets_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_tickets_holder FOREIGN KEY (holder_id) REFERENCES users (id),
    CONSTRAINT fk_tickets_contribution FOREIGN KEY (contribution_id) REFERENCES contributions (id)
);

-- ============================================================
-- applications
-- ============================================================
CREATE TABLE applications (
    id          UUID         NOT NULL,
    work_id     UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    status      VARCHAR(255) NOT NULL,
    message     TEXT,
    created_at  TIMESTAMP(6) NOT NULL,
    reviewed_at TIMESTAMP(6),
    CONSTRAINT pk_applications PRIMARY KEY (id),
    CONSTRAINT fk_applications_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_applications_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- ============================================================
-- work_follows
-- ============================================================
CREATE TABLE work_follows (
    id          UUID         NOT NULL,
    work_id     UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    followed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_work_follows PRIMARY KEY (id),
    CONSTRAINT uk_work_follows_work_user UNIQUE (work_id, user_id),
    CONSTRAINT fk_work_follows_work FOREIGN KEY (work_id) REFERENCES works (id),
    CONSTRAINT fk_work_follows_user FOREIGN KEY (user_id) REFERENCES users (id)
);
