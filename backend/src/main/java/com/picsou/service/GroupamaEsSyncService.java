package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.GroupamaEsSession;
import com.picsou.model.GroupamaEsSyncStatus;
import com.picsou.port.GroupamaEsErrorCode;
import com.picsou.port.GroupamaEsPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.GroupamaEsSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

@Service
public class GroupamaEsSyncService {
    private static final Logger log = LoggerFactory.getLogger(GroupamaEsSyncService.class);
    public static final String PROVIDER = "Groupama Épargne Salariale";
    private static final BigDecimal ABSOLUTE_RECONCILIATION_TOLERANCE = new BigDecimal("0.05");
    private static final BigDecimal RELATIVE_RECONCILIATION_TOLERANCE = new BigDecimal("0.001");

    private final GroupamaEsPort port;
    private final GroupamaEsSessionRepository sessionRepository;
    private final AccountRepository accountRepository;
    private final AccountHoldingRepository holdingRepository;
    private final FamilyMemberRepository memberRepository;
    private final AccountService accountService;
    private final CryptoEncryption encryption;
    private final TransactionTemplate txTemplate;
    private final Executor syncExecutor;

    public GroupamaEsSyncService(
        GroupamaEsPort port,
        GroupamaEsSessionRepository sessionRepository,
        AccountRepository accountRepository,
        AccountHoldingRepository holdingRepository,
        FamilyMemberRepository memberRepository,
        AccountService accountService,
        CryptoEncryption encryption,
        TransactionTemplate txTemplate,
        @Qualifier("groupamaEsSyncExecutor") Executor syncExecutor
    ) {
        this.port = port;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.holdingRepository = holdingRepository;
        this.memberRepository = memberRepository;
        this.accountService = accountService;
        this.encryption = encryption;
        this.txTemplate = txTemplate;
        this.syncExecutor = syncExecutor;
    }

    public AuthInitResponse initiateAuth(String login, String password, Long memberId) {
        GroupamaEsPort.InitiateResult result = port.initiateAuth(login, password);
        if (result.mfaRequired()) {
            if (result.processId() == null || result.processId().isBlank()) {
                throw error(
                    GroupamaEsErrorCode.INVALID_DATA,
                    "Groupama ES did not return an authentication process",
                    null
                );
            }
        } else {
            if (result.sessionState() == null || result.sessionState().isBlank()) {
                throw error(
                    GroupamaEsErrorCode.INVALID_DATA,
                    "Groupama ES did not return a session",
                    null
                );
            }
            storeSessionAndQueue(result.sessionState(), memberId);
        }
        return new AuthInitResponse(
            result.processId(),
            result.mfaRequired(),
            result.mfaType()
        );
    }

    public SessionStatusResponse completeAuth(String processId, String code, Long memberId) {
        String plainState = port.completeAuth(processId, code);
        if (plainState == null || plainState.isBlank()) {
            throw error(
                GroupamaEsErrorCode.INVALID_DATA,
                "Groupama ES did not return a session",
                null
            );
        }
        return storeSessionAndQueue(plainState, memberId);
    }

    public SessionStatusResponse queueSync(Long memberId) {
        QueueDecision decision = requireTransactionResult(txTemplate.execute(status -> {
            GroupamaEsSession session = sessionRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> error(
                    GroupamaEsErrorCode.SESSION_EXPIRED,
                    "No active Groupama ES session. Please reconnect.",
                    null
                ));
            if (!session.isActive()) {
                throw error(
                    GroupamaEsErrorCode.SESSION_EXPIRED,
                    "The Groupama ES session expired. Please reconnect.",
                    null
                );
            }
            if (session.getSyncStatus() == GroupamaEsSyncStatus.QUEUED
                || session.getSyncStatus() == GroupamaEsSyncStatus.RUNNING) {
                return new QueueDecision(null, toStatus(session));
            }

            String plainState = encryption.decrypt(session.getSessionState());
            session.markQueued();
            sessionRepository.save(session);
            return new QueueDecision(
                new SyncJob(session.getId(), memberId, plainState),
                toStatus(session)
            );
        }));

        if (decision.job() != null) {
            submit(decision.job());
            return getStatus(memberId);
        }
        return decision.status();
    }

    private SessionStatusResponse storeSessionAndQueue(String plainState, Long memberId) {
        SyncJob job = requireTransactionResult(txTemplate.execute(status -> {
            FamilyMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            sessionRepository.findByMemberIdForUpdate(memberId)
                .ifPresent(sessionRepository::delete);
            sessionRepository.flush();

            GroupamaEsSession newSession = GroupamaEsSession.create(
                member,
                encryption.encrypt(plainState),
                Instant.now()
            );
            newSession.markQueued();
            GroupamaEsSession stored = sessionRepository.saveAndFlush(newSession);
            return new SyncJob(stored.getId(), memberId, plainState);
        }));

        submit(job);
        return getStatus(memberId);
    }

    private void submit(SyncJob job) {
        try {
            syncExecutor.execute(() -> executeJob(job));
        } catch (RuntimeException ex) {
            markFailed(job, GroupamaEsErrorCode.INTERNAL_ERROR);
            throw error(
                GroupamaEsErrorCode.INTERNAL_ERROR,
                "Could not schedule the Groupama ES synchronization",
                ex
            );
        }
    }

    private void executeJob(SyncJob job) {
        if (!markRunning(job)) {
            return;
        }
        try {
            List<GroupamaEsPort.AccountData> fetched = port.fetchAccounts(job.plainState());
            List<PreparedAccount> prepared = prepareAccounts(fetched);
            if (commitPortfolio(job, prepared)) {
                log.info(
                    "Groupama ES sync completed (member={}; accounts={})",
                    job.memberId(),
                    prepared.size()
                );
            } else {
                log.info("Discarded stale Groupama ES sync result (member={})", job.memberId());
            }
        } catch (SyncException ex) {
            GroupamaEsErrorCode code = codeOf(ex);
            markFailed(job, code);
            log.warn("Groupama ES sync failed (member={}; code={})", job.memberId(), code);
        } catch (Exception ex) {
            markFailed(job, GroupamaEsErrorCode.INTERNAL_ERROR);
            log.error("Groupama ES sync failed unexpectedly (member={})", job.memberId(), ex);
        }
    }

    private boolean markRunning(SyncJob job) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<GroupamaEsSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("Groupama ES session disappeared before execution (member={})", job.memberId());
                return false;
            }
            GroupamaEsSession session = current.get();
            if (!session.isActive() || session.getSyncStatus() != GroupamaEsSyncStatus.QUEUED) {
                log.warn(
                    "Groupama ES sync cannot start from state {} (member={}; active={})",
                    session.getSyncStatus(),
                    job.memberId(),
                    session.isActive()
                );
                return false;
            }
            session.markRunning(Instant.now());
            sessionRepository.save(session);
            return true;
        }));
    }

    private List<PreparedAccount> prepareAccounts(List<GroupamaEsPort.AccountData> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            throw error(
                GroupamaEsErrorCode.PORTFOLIO_INCOMPLETE,
                "Groupama ES returned no complete savings accounts",
                null
            );
        }

        Set<String> externalIds = new HashSet<>();
        List<PreparedAccount> prepared = new ArrayList<>();
        for (GroupamaEsPort.AccountData account : fetched) {
            if (account == null || !account.snapshotComplete()) {
                throw error(
                    GroupamaEsErrorCode.PORTFOLIO_INCOMPLETE,
                    "Groupama ES returned an incomplete portfolio",
                    null
                );
            }
            String externalId = stableExternalId(account.externalId());
            if (!externalIds.add(externalId)) {
                throw error(
                    GroupamaEsErrorCode.INVALID_DATA,
                    "Groupama ES returned duplicate accounts",
                    null
                );
            }
            if (account.type() != AccountType.PEE && account.type() != AccountType.PERCOL) {
                throw error(
                    GroupamaEsErrorCode.INVALID_DATA,
                    "Groupama ES returned an unsupported account type",
                    null
                );
            }
            if (account.balanceEur() == null
                || account.balanceEur().signum() < 0
                || account.positions() == null) {
                throw error(
                    GroupamaEsErrorCode.INVALID_DATA,
                    "Groupama ES returned incomplete account values",
                    null
                );
            }

            List<PreparedPosition> positions = preparePositions(account.positions());
            BigDecimal positionValue = positions.stream()
                .map(PreparedPosition::currentValueEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!moneyClose(positionValue, account.balanceEur())) {
                throw error(
                    GroupamaEsErrorCode.PORTFOLIO_INCOMPLETE,
                    "Groupama ES returned an incomplete portfolio",
                    null
                );
            }

            prepared.add(new PreparedAccount(
                externalId,
                limit(account.name(), 100, "Groupama employee savings"),
                account.type(),
                account.balanceEur(),
                investedAmount(account.balanceEur(), positions),
                positions
            ));
        }
        return List.copyOf(prepared);
    }

    private List<PreparedPosition> preparePositions(
        List<GroupamaEsPort.Position> rawPositions
    ) {
        Map<String, PreparedPosition> positions = new LinkedHashMap<>();
        for (GroupamaEsPort.Position position : rawPositions) {
            if (position == null
                || position.quantity() == null
                || position.currentPriceEur() == null
                || position.currentValueEur() == null) {
                throw error(
                    GroupamaEsErrorCode.INVALID_DATA,
                    "Groupama ES returned an incomplete investment",
                    null
                );
            }
            if (position.quantity().signum() <= 0
                || position.currentPriceEur().signum() < 0
                || position.currentValueEur().signum() < 0
                || (position.pnlEur() != null
                    && position.currentValueEur()
                        .subtract(position.pnlEur())
                        .signum() < 0)) {
                throw error(
                    GroupamaEsErrorCode.INVALID_DATA,
                    "Groupama ES returned an invalid investment value",
                    null
                );
            }
            String ticker = clean(position.symbol());
            if (ticker == null
                || ticker.length() > 30
                || !ticker.matches("[A-Za-z0-9._:-]+")) {
                throw error(
                    GroupamaEsErrorCode.INVALID_DATA,
                    "Groupama ES returned an invalid investment identifier",
                    null
                );
            }
            if (!moneyClose(
                position.quantity().multiply(position.currentPriceEur()),
                position.currentValueEur()
            )) {
                throw error(
                    GroupamaEsErrorCode.INVALID_DATA,
                    "Groupama ES returned inconsistent investment units",
                    null
                );
            }

            BigDecimal averageBuyIn = averageBuyIn(position);
            PreparedPosition prepared = new PreparedPosition(
                ticker,
                limit(position.label(), 100, ticker),
                position.quantity(),
                averageBuyIn,
                position.currentPriceEur(),
                position.currentValueEur(),
                position.pnlEur()
            );
            positions.merge(ticker, prepared, this::mergePositions);
        }
        return List.copyOf(positions.values());
    }

    private BigDecimal averageBuyIn(GroupamaEsPort.Position position) {
        if (position.pnlEur() == null) {
            // A missing Groupama P&L must remain neutral rather than becoming
            // a fictitious gain from a zero cost basis.
            return position.currentValueEur().divide(
                position.quantity(),
                8,
                RoundingMode.HALF_UP
            );
        }
        return position.currentValueEur()
            .subtract(position.pnlEur())
            .divide(position.quantity(), 8, RoundingMode.HALF_UP);
    }

    private PreparedPosition mergePositions(
        PreparedPosition left,
        PreparedPosition right
    ) {
        BigDecimal quantity = left.quantity().add(right.quantity());
        BigDecimal value = left.currentValueEur().add(right.currentValueEur());
        BigDecimal pnl = sumComplete(left.pnlEur(), right.pnlEur());
        BigDecimal averageBuyIn = pnl == null
            ? value.divide(quantity, 8, RoundingMode.HALF_UP)
            : value.subtract(pnl).divide(quantity, 8, RoundingMode.HALF_UP);
        return new PreparedPosition(
            left.ticker(),
            right.name() != null ? right.name() : left.name(),
            quantity,
            averageBuyIn,
            value.divide(quantity, 8, RoundingMode.HALF_UP),
            value,
            pnl
        );
    }

    private BigDecimal sumComplete(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.add(right);
    }

    private BigDecimal investedAmount(
        BigDecimal balance,
        List<PreparedPosition> positions
    ) {
        BigDecimal invested = BigDecimal.ZERO;
        for (PreparedPosition position : positions) {
            if (position.pnlEur() == null) {
                return balance;
            }
            invested = invested.add(
                position.currentValueEur().subtract(position.pnlEur())
            );
        }
        return invested;
    }

    private boolean commitPortfolio(SyncJob job, List<PreparedAccount> prepared) {
        return Boolean.TRUE.equals(txTemplate.execute(status -> {
            Optional<GroupamaEsSession> current = sessionRepository.findByIdAndMemberIdForUpdate(
                job.sessionId(),
                job.memberId()
            );
            if (current.isEmpty()) {
                log.info("Groupama ES session disappeared before commit (member={})", job.memberId());
                return false;
            }
            GroupamaEsSession session = current.get();
            if (!session.isActive()
                || session.getSyncStatus() != GroupamaEsSyncStatus.RUNNING) {
                log.warn(
                    "Groupama ES sync cannot commit from state {} (member={}; active={})",
                    session.getSyncStatus(),
                    job.memberId(),
                    session.isActive()
                );
                return false;
            }

            FamilyMember member = memberRepository.findById(job.memberId())
                .orElseThrow(() -> new ResourceNotFoundException("Family member not found"));
            Instant syncedAt = Instant.now();
            for (PreparedAccount account : prepared) {
                upsertAccount(account, member, job.memberId(), syncedAt);
            }

            session.markSuccessful(syncedAt);
            sessionRepository.save(session);
            return true;
        }));
    }

    private void upsertAccount(
        PreparedAccount data,
        FamilyMember member,
        Long memberId,
        Instant syncedAt
    ) {
        Optional<Account> existing = accountRepository
            .findByExternalAccountIdAndMemberId(data.externalId(), memberId);
        if (existing.isEmpty()
            && accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(
                data.externalId(),
                memberId
            )) {
            log.info("Groupama ES skipped a soft-deleted account (member={})", memberId);
            return;
        }

        Account account = existing.orElseGet(() -> Account.builder()
            .member(member)
            .externalAccountId(data.externalId())
            .provider(PROVIDER)
            .currency("EUR")
            .isManual(false)
            .color(data.type() == AccountType.PEE ? "#16a34a" : "#7c3aed")
            .build());
        account.setName(data.name());
        account.setType(data.type());
        account.setProvider(PROVIDER);
        account.setCurrency("EUR");
        account.setManual(false);
        account.setCurrentBalance(data.balanceEur());
        account.setCashBalance(null);
        account.setLastSyncedAt(syncedAt);
        Account savedAccount = accountRepository.save(account);

        holdingRepository.deleteByAccountId(savedAccount.getId());
        holdingRepository.flush();
        List<AccountHolding> holdings = data.positions().stream()
            .map(position -> AccountHolding.builder()
                .account(savedAccount)
                .ticker(position.ticker())
                .name(position.name())
                .quantity(position.quantity())
                .averageBuyIn(position.averageBuyInEur())
                .currentPrice(position.currentPriceEur())
                .quoteCurrency("EUR")
                .providerValueEur(position.currentValueEur())
                .providerPnlEur(position.pnlEur())
                .lastSyncedAt(syncedAt)
                .build())
            .toList();
        holdingRepository.saveAll(holdings);
        holdingRepository.flush();

        accountService.upsertSnapshot(
            savedAccount,
            data.balanceEur(),
            data.investedAmountEur(),
            LocalDate.now()
        );
    }

    private void markFailed(SyncJob job, GroupamaEsErrorCode code) {
        try {
            txTemplate.executeWithoutResult(status -> {
                Optional<GroupamaEsSession> current =
                    sessionRepository.findByIdAndMemberIdForUpdate(
                        job.sessionId(),
                        job.memberId()
                    );
                if (current.isEmpty()) {
                    log.info(
                        "Groupama ES session disappeared before failure was recorded (member={})",
                        job.memberId()
                    );
                    return;
                }
                GroupamaEsSession session = current.get();
                if (!session.isSyncInFlight()) {
                    log.warn(
                        "Groupama ES sync failure ignored from state {} (member={}; code={})",
                        session.getSyncStatus(),
                        job.memberId(),
                        code
                    );
                    return;
                }
                session.markFailed(code, Instant.now());
                sessionRepository.save(session);
            });
        } catch (RuntimeException ex) {
            log.error(
                "Could not persist Groupama ES sync failure (member={}; code={})",
                job.memberId(),
                code,
                ex
            );
        }
    }

    @Transactional
    public void recoverInterruptedSyncs() {
        int recovered = sessionRepository.markInterruptedSyncsFailed(
            List.of(GroupamaEsSyncStatus.QUEUED, GroupamaEsSyncStatus.RUNNING),
            GroupamaEsSyncStatus.FAILED,
            Instant.now(),
            GroupamaEsErrorCode.INTERNAL_ERROR
        );
        if (recovered > 0) {
            log.warn("Recovered {} interrupted Groupama ES sync job(s)", recovered);
        }
    }

    @Transactional(readOnly = true)
    public SessionStatusResponse getStatus(Long memberId) {
        return sessionRepository.findByMemberId(memberId)
            .map(this::toStatus)
            .orElseGet(SessionStatusResponse::inactive);
    }

    public void clearSession(Long memberId) {
        txTemplate.executeWithoutResult(status ->
            sessionRepository.findByMemberIdForUpdate(memberId)
                .ifPresent(sessionRepository::delete)
        );
    }

    public void resyncIfSessionActive(Long memberId) {
        try {
            SessionStatusResponse status = getStatus(memberId);
            if (status.isActive()) {
                queueSync(memberId);
            }
        } catch (ResourceNotFoundException ex) {
            log.debug("Member disappeared before scheduled Groupama ES sync (member={})", memberId);
        } catch (DataAccessException ex) {
            log.error("Database error during scheduled Groupama ES sync (member={})", memberId, ex);
        } catch (SyncException ex) {
            log.warn(
                "Could not queue scheduled Groupama ES sync (member={}; code={})",
                memberId,
                codeOf(ex)
            );
        } catch (RuntimeException ex) {
            log.error("Unexpected scheduled Groupama ES sync failure (member={})", memberId, ex);
        }
    }

    private SessionStatusResponse toStatus(GroupamaEsSession session) {
        return new SessionStatusResponse(
            session.isActive(),
            null,
            session.getSyncStatus(),
            session.getLastSyncStartedAt(),
            session.getLastSyncCompletedAt(),
            session.getLastSyncError()
        );
    }

    private GroupamaEsErrorCode codeOf(SyncException exception) {
        if (exception.getCode() == null) {
            return GroupamaEsErrorCode.UPSTREAM_UNAVAILABLE;
        }
        try {
            return GroupamaEsErrorCode.valueOf(exception.getCode());
        } catch (IllegalArgumentException ignored) {
            return GroupamaEsErrorCode.UPSTREAM_UNAVAILABLE;
        }
    }

    private SyncException error(
        GroupamaEsErrorCode code,
        String message,
        Throwable cause
    ) {
        return new SyncException(message, cause, code.name());
    }

    private String stableExternalId(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            throw error(
                GroupamaEsErrorCode.INVALID_DATA,
                "Groupama ES returned an invalid account identifier",
                null
            );
        }
        String externalId = cleaned.startsWith("ges_") ? cleaned : "ges_" + cleaned;
        if (externalId.length() > 100) {
            throw error(
                GroupamaEsErrorCode.INVALID_DATA,
                "Groupama ES returned an invalid account identifier",
                null
            );
        }
        return externalId;
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String limit(String value, int maxLength, String fallback) {
        String cleaned = clean(value);
        if (cleaned == null) {
            cleaned = fallback;
        }
        return cleaned.length() <= maxLength
            ? cleaned
            : cleaned.substring(0, maxLength);
    }

    private boolean moneyClose(BigDecimal actual, BigDecimal expected) {
        BigDecimal tolerance = ABSOLUTE_RECONCILIATION_TOLERANCE.max(
            expected.abs().multiply(RELATIVE_RECONCILIATION_TOLERANCE)
        );
        return actual.subtract(expected).abs().compareTo(tolerance) <= 0;
    }

    private <T> T requireTransactionResult(T value) {
        return Objects.requireNonNull(value, "Transaction callback returned no result");
    }

    public record AuthInitResponse(
        String processId,
        boolean mfaRequired,
        String mfaType
    ) {}

    public record SessionStatusResponse(
        boolean isActive,
        Instant expiresAt,
        GroupamaEsSyncStatus syncStatus,
        Instant lastSyncStartedAt,
        Instant lastSyncCompletedAt,
        GroupamaEsErrorCode lastSyncError
    ) {
        static SessionStatusResponse inactive() {
            return new SessionStatusResponse(
                false,
                null,
                GroupamaEsSyncStatus.IDLE,
                null,
                null,
                null
            );
        }
    }

    private record QueueDecision(SyncJob job, SessionStatusResponse status) {}
    private record SyncJob(Long sessionId, Long memberId, String plainState) {}
    private record PreparedAccount(
        String externalId,
        String name,
        AccountType type,
        BigDecimal balanceEur,
        BigDecimal investedAmountEur,
        List<PreparedPosition> positions
    ) {}
    private record PreparedPosition(
        String ticker,
        String name,
        BigDecimal quantity,
        BigDecimal averageBuyInEur,
        BigDecimal currentPriceEur,
        BigDecimal currentValueEur,
        BigDecimal pnlEur
    ) {}
}
