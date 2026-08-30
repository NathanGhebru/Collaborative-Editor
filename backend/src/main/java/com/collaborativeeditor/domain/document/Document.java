package com.collaborativeeditor.domain.document;

import com.collaborativeeditor.domain.user.User;
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
@Table(name = "documents")
public class Document {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "sync_epoch", nullable = false)
    private UUID syncEpoch;

    @Column(name = "current_revision", nullable = false)
    private Long currentRevision;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Document() {
    }

    public Document(
            UUID id,
            User owner,
            String title,
            UUID syncEpoch,
            Long currentRevision,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        this.id = id;
        this.owner = owner;
        this.title = title;
        this.syncEpoch = syncEpoch;
        this.currentRevision = currentRevision;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public UUID getSyncEpoch() {
        return syncEpoch;
    }

    public void setSyncEpoch(UUID syncEpoch) {
        this.syncEpoch = syncEpoch;
    }

    public Long getCurrentRevision() {
        return currentRevision;
    }

    public void setCurrentRevision(Long currentRevision) {
        this.currentRevision = currentRevision;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

