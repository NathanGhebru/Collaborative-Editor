package com.collaborativeeditor.domain.document;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class DocumentOperationIdPk implements Serializable {

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "sync_epoch", nullable = false)
    private UUID syncEpoch;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "client_operation_id", nullable = false)
    private UUID clientOperationId;

    public DocumentOperationIdPk() {
    }

    public DocumentOperationIdPk(UUID documentId, UUID syncEpoch, UUID clientId, UUID clientOperationId) {
        this.documentId = documentId;
        this.syncEpoch = syncEpoch;
        this.clientId = clientId;
        this.clientOperationId = clientOperationId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID getSyncEpoch() {
        return syncEpoch;
    }

    public void setSyncEpoch(UUID syncEpoch) {
        this.syncEpoch = syncEpoch;
    }

    public UUID getClientId() {
        return clientId;
    }

    public void setClientId(UUID clientId) {
        this.clientId = clientId;
    }

    public UUID getClientOperationId() {
        return clientOperationId;
    }

    public void setClientOperationId(UUID clientOperationId) {
        this.clientOperationId = clientOperationId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocumentOperationIdPk that = (DocumentOperationIdPk) o;
        return Objects.equals(documentId, that.documentId) &&
               Objects.equals(syncEpoch, that.syncEpoch) &&
               Objects.equals(clientId, that.clientId) &&
               Objects.equals(clientOperationId, that.clientOperationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, syncEpoch, clientId, clientOperationId);
    }
}

