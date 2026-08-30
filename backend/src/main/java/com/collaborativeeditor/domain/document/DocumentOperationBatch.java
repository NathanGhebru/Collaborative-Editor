package com.collaborativeeditor.domain.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "document_operation_batches")
public class DocumentOperationBatch {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "sync_epoch", nullable = false)
    private UUID syncEpoch;

    @Column(name = "first_revision", nullable = false)
    private Long firstRevision;

    @Column(name = "last_revision", nullable = false)
    private Long lastRevision;

    @Column(name = "operations", nullable = false, columnDefinition = "TEXT")
    private String operations;

    @Column(name = "operation_count", nullable = false)
    private Integer operationCount;

    @Column(name = "content_hash_after", length = 64)
    private String contentHashAfter;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public DocumentOperationBatch() {
    }

    public DocumentOperationBatch(
            UUID id,
            Document document,
            UUID syncEpoch,
            Long firstRevision,
            Long lastRevision,
            String operations,
            Integer operationCount,
            String contentHashAfter,
            OffsetDateTime createdAt) {
        this.id = id;
        this.document = document;
        this.syncEpoch = syncEpoch;
        this.firstRevision = firstRevision;
        this.lastRevision = lastRevision;
        this.operations = operations;
        this.operationCount = operationCount;
        this.contentHashAfter = contentHashAfter;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Document getDocument() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
    }

    public UUID getSyncEpoch() {
        return syncEpoch;
    }

    public void setSyncEpoch(UUID syncEpoch) {
        this.syncEpoch = syncEpoch;
    }

    public Long getFirstRevision() {
        return firstRevision;
    }

    public void setFirstRevision(Long firstRevision) {
        this.firstRevision = firstRevision;
    }

    public Long getLastRevision() {
        return lastRevision;
    }

    public void setLastRevision(Long lastRevision) {
        this.lastRevision = lastRevision;
    }

    public String getOperations() {
        return operations;
    }

    public void setOperations(String operations) {
        this.operations = operations;
    }

    public Integer getOperationCount() {
        return operationCount;
    }

    public void setOperationCount(Integer operationCount) {
        this.operationCount = operationCount;
    }

    public String getContentHashAfter() {
        return contentHashAfter;
    }

    public void setContentHashAfter(String contentHashAfter) {
        this.contentHashAfter = contentHashAfter;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

