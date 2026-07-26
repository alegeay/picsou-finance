package com.picsou.service;

import com.picsou.config.CryptoEncryption;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class GroupamaEsSyncServiceTest {
    @Mock GroupamaEsPort port;
    @Mock GroupamaEsSessionRepository sessionRepository;
    @Mock AccountRepository accountRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock AccountService accountService;
    @Mock CryptoEncryption encryption;
    @Mock TransactionTemplate txTemplate;
    @Mock TransactionStatus transactionStatus;
    @Captor ArgumentCaptor<Account> accountCaptor;
    @Captor ArgumentCaptor<List<AccountHolding>> holdingsCaptor;
    @Captor ArgumentCaptor<GroupamaEsSession> sessionCaptor;

    GroupamaEsSyncService service;

    @BeforeEach
    void setUp() {
        executeTransactionsImmediately();
        service = serviceWith(Runnable::run);
    }

    @Test
    void queueSyncCommitsACompleteReconciledPlanAtomically() {
        FamilyMember member = member();
        GroupamaEsSession session = activeSession(member);
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(completePee()));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        Account existingAccount = Account.builder()
            .id(11L)
            .member(member)
            .name("Edited account")
            .type(AccountType.OTHER)
            .provider("Edited provider")
            .currency("USD")
            .currentBalance(BigDecimal.ZERO)
            .isManual(true)
            .build();
        when(accountRepository.findByExternalAccountIdAndMemberId("ges_pee-123", 7L))
            .thenReturn(Optional.of(existingAccount));
        when(accountRepository.save(any(Account.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        GroupamaEsSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(GroupamaEsSyncStatus.SUCCESS);
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getProvider()).isEqualTo(GroupamaEsSyncService.PROVIDER);
        assertThat(savedAccount.getCurrency()).isEqualTo("EUR");
        assertThat(savedAccount.isManual()).isFalse();
        assertThat(savedAccount.getType()).isEqualTo(AccountType.PEE);
        assertThat(savedAccount.getCurrentBalance()).isEqualByComparingTo("1000");

        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        AccountHolding holding = holdingsCaptor.getValue().getFirst();
        assertThat(holding.getTicker()).isEqualTo("GES_FCPE_1");
        assertThat(holding.getQuoteCurrency()).isEqualTo("EUR");
        assertThat(holding.getAverageBuyIn()).isEqualByComparingTo("80");
        assertThat(holding.getCurrentPrice()).isEqualByComparingTo("100");
        assertThat(holding.getProviderValueEur()).isEqualByComparingTo("1000");
        assertThat(holding.getProviderPnlEur()).isEqualByComparingTo("200");

        InOrder writeOrder = inOrder(holdingRepository, accountService);
        writeOrder.verify(holdingRepository).deleteByAccountId(11L);
        writeOrder.verify(holdingRepository).flush();
        writeOrder.verify(holdingRepository).saveAll(any());
        writeOrder.verify(holdingRepository).flush();
        writeOrder.verify(accountService).upsertSnapshot(
            any(Account.class),
            eq(new BigDecimal("1000")),
            eq(new BigDecimal("800")),
            eq(LocalDate.now())
        );
    }

    @Test
    void reconciliationMismatchFailsBeforeExistingHoldingsAreTouched() {
        GroupamaEsSession session = activeSession(member());
        arrangeQueuedSession(session);
        GroupamaEsPort.AccountData mismatched = new GroupamaEsPort.AccountData(
            "pee-123",
            "PEE Groupama",
            AccountType.PEE,
            new BigDecimal("1200"),
            List.of(position("GES_FCPE_1", "Fund", "10", "100", "1000", "200")),
            true
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(mismatched));

        GroupamaEsSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(GroupamaEsSyncStatus.FAILED);
        assertThat(result.lastSyncError())
            .isEqualTo(GroupamaEsErrorCode.PORTFOLIO_INCOMPLETE);
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void incompleteSnapshotFailsBeforeExistingHoldingsAreTouched() {
        GroupamaEsSession session = activeSession(member());
        arrangeQueuedSession(session);
        GroupamaEsPort.AccountData incomplete = new GroupamaEsPort.AccountData(
            "pee-123",
            "PEE Groupama",
            AccountType.PEE,
            new BigDecimal("1000"),
            List.of(position("GES_FCPE_1", "Fund", "10", "100", "1000", null)),
            false
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(incomplete));

        GroupamaEsSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.lastSyncError())
            .isEqualTo(GroupamaEsErrorCode.PORTFOLIO_INCOMPLETE);
        verify(holdingRepository, never()).deleteByAccountId(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void missingProviderPnlKeepsACompletePlanAtNeutralCostBasis() {
        FamilyMember member = member();
        GroupamaEsSession session = activeSession(member);
        arrangeQueuedSession(session);
        GroupamaEsPort.AccountData account = new GroupamaEsPort.AccountData(
            "percol-123",
            "PERCOL Groupama",
            AccountType.PERCOL,
            new BigDecimal("1000"),
            List.of(position("GES_PROFILE_1", "Gestion pilotée", "10", "100", "1000", null)),
            true
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        arrangeNewAccountPersistence(12L);

        service.queueSync(7L);

        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getType()).isEqualTo(AccountType.PERCOL);
        verify(holdingRepository).saveAll(holdingsCaptor.capture());
        AccountHolding holding = holdingsCaptor.getValue().getFirst();
        assertThat(holding.getAverageBuyIn()).isEqualByComparingTo("100");
        assertThat(holding.getProviderPnlEur()).isNull();
        verify(accountService).upsertSnapshot(
            any(Account.class),
            eq(new BigDecimal("1000")),
            eq(new BigDecimal("1000")),
            eq(LocalDate.now())
        );
    }

    @Test
    void impossibleProviderProfitRejectsTheSnapshotBeforeWrites() {
        GroupamaEsSession session = activeSession(member());
        arrangeQueuedSession(session);
        GroupamaEsPort.AccountData account = new GroupamaEsPort.AccountData(
            "pee-123",
            "PEE Groupama",
            AccountType.PEE,
            new BigDecimal("1000"),
            List.of(position("GES_FCPE_1", "Fund", "10", "100", "1000", "1200")),
            true
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(account));

        GroupamaEsSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.lastSyncError()).isEqualTo(GroupamaEsErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void expiredSessionIsInvalidatedAndRemainsObservable() {
        GroupamaEsSession session = activeSession(member());
        arrangeQueuedSession(session);
        when(port.fetchAccounts("plain-state")).thenThrow(new SyncException(
            "Session expired",
            null,
            GroupamaEsErrorCode.SESSION_EXPIRED.name()
        ));

        GroupamaEsSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.isActive()).isFalse();
        assertThat(result.syncStatus()).isEqualTo(GroupamaEsSyncStatus.FAILED);
        assertThat(result.lastSyncError())
            .isEqualTo(GroupamaEsErrorCode.SESSION_EXPIRED);
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void authenticationStoresOnlyEncryptedStateAndQueuesInitialSync() {
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        GroupamaEsSyncService delayedService = serviceWith(queuedTask::set);
        FamilyMember member = member();
        GroupamaEsSession stored = GroupamaEsSession.builder()
            .id(9L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(GroupamaEsSyncStatus.QUEUED)
            .build();
        when(port.initiateAuth("login", "password")).thenReturn(
            new GroupamaEsPort.InitiateResult(null, false, null, "plain-state")
        );
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(sessionRepository.findByMemberIdForUpdate(7L)).thenReturn(Optional.empty());
        when(encryption.encrypt("plain-state")).thenReturn("encrypted");
        when(sessionRepository.saveAndFlush(any(GroupamaEsSession.class))).thenReturn(stored);
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(stored));

        GroupamaEsSyncService.AuthInitResponse result =
            delayedService.initiateAuth("login", "password", 7L);

        assertThat(result.mfaRequired()).isFalse();
        assertThat(queuedTask.get()).isNotNull();
        verify(sessionRepository).saveAndFlush(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getSessionState()).isEqualTo("encrypted");
        assertThat(sessionCaptor.getValue().getSessionState()).doesNotContain("plain");
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void authenticationRejectsMfaWithoutAProcessIdentifier() {
        when(port.initiateAuth("login", "password")).thenReturn(
            new GroupamaEsPort.InitiateResult(null, true, "SMS", null)
        );

        assertThatThrownBy(() -> service.initiateAuth("login", "password", 7L))
            .isInstanceOfSatisfying(SyncException.class, ex ->
                assertThat(ex.getCode()).isEqualTo(GroupamaEsErrorCode.INVALID_DATA.name())
            );
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void queueSyncDoesNotScheduleDuplicateWhileAJobIsRunning() {
        GroupamaEsSession session = activeSession(member());
        session.markQueued();
        session.markRunning(Instant.now());
        when(sessionRepository.findByMemberIdForUpdate(7L))
            .thenReturn(Optional.of(session));

        GroupamaEsSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.syncStatus()).isEqualTo(GroupamaEsSyncStatus.RUNNING);
        verify(port, never()).fetchAccounts(any());
    }

    @Test
    void replacingTheSessionFencesAnOlderPortfolioResult() {
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        GroupamaEsSyncService delayedService = serviceWith(queuedTask::set);
        GroupamaEsSession session = activeSession(member());
        when(sessionRepository.findByMemberIdForUpdate(7L))
            .thenReturn(Optional.of(session));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");

        delayedService.queueSync(7L);

        when(sessionRepository.findByIdAndMemberIdForUpdate(3L, 7L))
            .thenReturn(Optional.of(session), Optional.empty());
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(completePee()));
        queuedTask.get().run();

        verify(accountRepository, never()).save(any());
        verify(holdingRepository, never()).deleteByAccountId(any());
    }

    @Test
    void unsupportedAccountTypeRejectsTheWholeSnapshot() {
        GroupamaEsSession session = activeSession(member());
        arrangeQueuedSession(session);
        GroupamaEsPort.AccountData unsupported = new GroupamaEsPort.AccountData(
            "other",
            "Other",
            AccountType.OTHER,
            BigDecimal.ZERO,
            List.of(),
            true
        );
        when(port.fetchAccounts("plain-state")).thenReturn(List.of(unsupported));

        GroupamaEsSyncService.SessionStatusResponse result = service.queueSync(7L);

        assertThat(result.lastSyncError()).isEqualTo(GroupamaEsErrorCode.INVALID_DATA);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void startupRecoveryMarksInterruptedJobsAsRetryableFailures() {
        when(sessionRepository.markInterruptedSyncsFailed(
            any(),
            eq(GroupamaEsSyncStatus.FAILED),
            any(),
            eq(GroupamaEsErrorCode.INTERNAL_ERROR)
        )).thenReturn(2);

        service.recoverInterruptedSyncs();

        verify(sessionRepository).markInterruptedSyncsFailed(
            eq(List.of(GroupamaEsSyncStatus.QUEUED, GroupamaEsSyncStatus.RUNNING)),
            eq(GroupamaEsSyncStatus.FAILED),
            any(),
            eq(GroupamaEsErrorCode.INTERNAL_ERROR)
        );
    }

    private void arrangeQueuedSession(GroupamaEsSession session) {
        when(sessionRepository.findByMemberIdForUpdate(7L))
            .thenReturn(Optional.of(session));
        when(sessionRepository.findByIdAndMemberIdForUpdate(session.getId(), 7L))
            .thenReturn(Optional.of(session));
        when(sessionRepository.findByMemberId(7L)).thenReturn(Optional.of(session));
        when(encryption.decrypt("encrypted")).thenReturn("plain-state");
    }

    private FamilyMember member() {
        return FamilyMember.builder().id(7L).displayName("Owner").build();
    }

    private GroupamaEsSession activeSession(FamilyMember member) {
        return GroupamaEsSession.builder()
            .id(3L)
            .member(member)
            .sessionState("encrypted")
            .active(true)
            .syncStatus(GroupamaEsSyncStatus.IDLE)
            .build();
    }

    private GroupamaEsPort.AccountData completePee() {
        return new GroupamaEsPort.AccountData(
            "pee-123",
            "PEE Groupama",
            AccountType.PEE,
            new BigDecimal("1000"),
            List.of(position("GES_FCPE_1", "Groupama Sélection ISR", "10", "100", "1000", "200")),
            true
        );
    }

    private GroupamaEsPort.Position position(
        String symbol,
        String label,
        String quantity,
        String currentPrice,
        String currentValue,
        String pnl
    ) {
        return new GroupamaEsPort.Position(
            symbol,
            label,
            new BigDecimal(quantity),
            new BigDecimal(currentPrice),
            new BigDecimal(currentValue),
            pnl == null ? null : new BigDecimal(pnl)
        );
    }

    private void arrangeNewAccountPersistence(Long id) {
        when(accountRepository.findByExternalAccountIdAndMemberId(anyString(), eq(7L)))
            .thenReturn(Optional.empty());
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(
            anyString(),
            eq(7L)
        )).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(id);
            return account;
        });
    }

    private GroupamaEsSyncService serviceWith(Executor executor) {
        return new GroupamaEsSyncService(
            port,
            sessionRepository,
            accountRepository,
            holdingRepository,
            memberRepository,
            accountService,
            encryption,
            txTemplate,
            executor
        );
    }

    private void executeTransactionsImmediately() {
        lenient().doAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(txTemplate).execute(any(TransactionCallback.class));
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(txTemplate).executeWithoutResult(any());
    }
}
