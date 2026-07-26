package com.picsou.repository;

import com.picsou.model.GroupamaEsSession;
import com.picsou.model.GroupamaEsSyncStatus;
import com.picsou.port.GroupamaEsErrorCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface GroupamaEsSessionRepository extends JpaRepository<GroupamaEsSession, Long> {
    Optional<GroupamaEsSession> findByMemberId(Long memberId);

    boolean existsByActiveTrue();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE GroupamaEsSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.syncStatus IN :interrupted
        """)
    int markInterruptedSyncsFailed(
        @Param("interrupted") Collection<GroupamaEsSyncStatus> interrupted,
        @Param("failed") GroupamaEsSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") GroupamaEsErrorCode errorCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from GroupamaEsSession session
        where session.id = :id and session.member.id = :memberId
        """)
    Optional<GroupamaEsSession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from GroupamaEsSession session where session.member.id = :memberId")
    Optional<GroupamaEsSession> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
