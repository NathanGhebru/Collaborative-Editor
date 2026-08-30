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
@Table(name = "document_snapshots")
public class DocumentSnapshot {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "sync_epoch", nullable = false)
    private UUID syncEpoch;

    @Column(name = "revision", nullable = false)
    private Long revision;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public DocumentSnapshot() {
    }

    public DocumentSnapshot(
            UUID id,
            Document document,
            UUID syncEpoch,
            Long revision,
            String content,
            String contentHash,
            OffsetDateTime createdAt) {
        this.id = id;
        this.document = document;
        this.syncEpoch = syncEpoch;
        this.revision = revision;
        this.content = content;
        this.contentHash = contentHash;
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

    public Long getRevision() {
        return revision;
    }

    public void setRevision(Long revision) {
        this.revision = revision;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}

