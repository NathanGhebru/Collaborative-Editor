ALTER TABLE document_snapshots DROP CONSTRAINT uk_document_snapshots_doc_rev;

ALTER TABLE document_snapshots ADD CONSTRAINT uk_document_snapshots_doc_epoch_rev UNIQUE (document_id, sync_epoch, revision);

DROP INDEX IF EXISTS idx_document_snapshots_doc_rev;

CREATE INDEX idx_document_snapshots_doc_epoch_rev ON document_snapshots(document_id, sync_epoch, revision DESC);

