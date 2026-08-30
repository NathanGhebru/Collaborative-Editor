CREATE TABLE documents (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    title VARCHAR(255) NOT NULL,
    sync_epoch UUID NOT NULL,
    current_revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_documents_owner_id ON documents(owner_id);
CREATE INDEX idx_documents_updated_at_id ON documents(updated_at DESC, id DESC);

CREATE TABLE document_snapshots (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    sync_epoch UUID NOT NULL,
    revision BIGINT NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_document_snapshots_doc_rev UNIQUE (document_id, revision)
);

CREATE INDEX idx_document_snapshots_doc_rev ON document_snapshots(document_id, revision DESC);

CREATE TABLE document_permissions (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL DEFAULT 'EDITOR',
    granted_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_document_permissions_doc_user UNIQUE (document_id, user_id),
    CONSTRAINT chk_document_permissions_role CHECK (role IN ('EDITOR'))
);

CREATE INDEX idx_document_permissions_user_id ON document_permissions(user_id);
CREATE INDEX idx_document_permissions_doc_user ON document_permissions(document_id, user_id);

