-- Keep V57-V64 byte-for-byte compatible with the currently deployed
-- pre-release migration lineage. IBKR therefore lands at V65 in this GitOps
-- branch even though upstream 1.0.15 introduced it at V57.
CREATE TABLE ibkr_connection (
    id              BIGSERIAL PRIMARY KEY,
    member_id       BIGINT        NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    token           VARCHAR(500)  NOT NULL,
    query_id        VARCHAR(500)  NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'CONNECTED',
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_ibkr_connection_member ON ibkr_connection(member_id);
