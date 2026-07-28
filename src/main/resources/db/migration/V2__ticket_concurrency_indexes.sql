-- Physical backstop: at most one ACTIVE ticket per work.
CREATE UNIQUE INDEX uk_tickets_one_active_per_work
    ON tickets (work_id) WHERE status = 'ACTIVE';

-- Support index: lookup tickets by (work, status).
CREATE INDEX idx_tickets_work_status ON tickets (work_id, status);

-- Support index: expiry sweep over ACTIVE tickets.
CREATE INDEX idx_tickets_active_expires ON tickets (expires_at) WHERE status = 'ACTIVE';
