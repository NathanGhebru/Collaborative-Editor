package com.collaborativeeditor.domain.document;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "document_operation_ids")
public class DocumentOperationId {

    @EmbeddedId
    private DocumentOperationIdPk id;

    @Column(name = "revision", nullable = false)
    private Long revision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private DocumentOperationBatch batch;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public DocumentOperationId() {
    }

    public DocumentOperationId(
            DocumentOperationIdPk id,
            Long revision,
            DocumentOperationBatch batch,
            OffsetDateTime createdAt) {
        this.id = id;
        this.revision = revision;
        this.batch = batch;
        this.createdAt = createdAt;
    }

    public DocumentOperationIdPk getId() {
        return id;
    }

    public void setId(DocumentOperationIdPk id) {
        this.id = id;
    }

    public Long getRevision() {
        return revision;
    }

    public void setRevision(Long revision) {
        this.revision = revision;
    }

    public DocumentOperationBatch getBatch() {
        return batch;
    }

    public void setBatch(DocumentOperationBatch batch) {
        this.batch = batch;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

