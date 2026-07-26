package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.port.GroupamaEsErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "groupama_es_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class GroupamaEsSession extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    /** Complete Playwright storage state, encrypted via CryptoEncryption. */
    @Column(name = "session_state", nullable = false, columnDefinition = "TEXT")
    private String sessionState;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 16)
    @Builder.Default
    private GroupamaEsSyncStatus syncStatus = GroupamaEsSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private GroupamaEsErrorCode lastSyncError;

    public static GroupamaEsSession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        return GroupamaEsSession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(GroupamaEsSyncStatus.IDLE)
            .build();
    }

    public void markQueued() {
        if (!active) {
            throw new IllegalStateException("Inactive Groupama ES sessions cannot be queued");
        }
        if (isSyncInFlight()) {
            throw new IllegalStateException("Groupama ES synchronization is already in progress");
        }
        syncStatus = GroupamaEsSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != GroupamaEsSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued Groupama ES session can run");
        }
        syncStatus = GroupamaEsSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != GroupamaEsSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running Groupama ES session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = GroupamaEsSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(GroupamaEsErrorCode errorCode, Instant completedAt) {
        if (!isSyncInFlight()) {
            throw new IllegalStateException("Only an in-flight Groupama ES synchronization can fail");
        }
        GroupamaEsErrorCode error = Objects.requireNonNull(errorCode, "errorCode");
        syncStatus = GroupamaEsSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        if (error == GroupamaEsErrorCode.SESSION_EXPIRED) {
            active = false;
        }
    }

    public boolean isSyncInFlight() {
        return syncStatus == GroupamaEsSyncStatus.QUEUED
            || syncStatus == GroupamaEsSyncStatus.RUNNING;
    }
}
