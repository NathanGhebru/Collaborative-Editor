CREATE TABLE document_operation_batches (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    sync_epoch UUID NOT NULL,
    first_revision BIGINT NOT NULL,
    last_revision BIGINT NOT NULL,
    operations TEXT NOT NULL,
    operation_count INTEGER NOT NULL,
    content_hash_after VARCHAR(64) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_doc_op_batches_rev CHECK (first_revision <= last_revision),
    CONSTRAINT chk_doc_op_batches_count CHECK (operation_count > 0),
    CONSTRAINT uk_doc_op_batches_first_rev UNIQUE (document_id, sync_epoch, first_revision),
    CONSTRAINT uk_doc_op_batches_last_rev UNIQUE (document_id, sync_epoch, last_revision)
);

CREATE INDEX idx_doc_op_batches_range ON document_operation_batches(document_id, sync_epoch, first_revision ASC, last_revision ASC);

CREATE TABLE document_operation_ids (
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    sync_epoch UUID NOT NULL,
    client_id UUID NOT NULL,
    client_operation_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    batch_id UUID NOT NULL REFERENCES document_operation_batches(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, sync_epoch, client_id, client_operation_id)
);

CREATE INDEX idx_doc_op_ids_batch_id ON document_operation_ids(batch_id);

