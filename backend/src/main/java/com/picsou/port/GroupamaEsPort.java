package com.picsou.port;

import com.picsou.model.AccountType;

import java.math.BigDecimal;
import java.util.List;

public interface GroupamaEsPort {
    InitiateResult initiateAuth(String login, String password);
    String completeAuth(String processId, String code);
    List<AccountData> fetchAccounts(String sessionState);

    record InitiateResult(
        String processId,
        boolean mfaRequired,
        String mfaType,
        String sessionState
    ) {}

    record Position(
        String symbol,
        String label,
        BigDecimal quantity,
        BigDecimal currentPriceEur,
        BigDecimal currentValueEur,
        BigDecimal pnlEur
    ) {}

    record AccountData(
        String externalId,
        String name,
        AccountType type,
        BigDecimal balanceEur,
        List<Position> positions,
        boolean snapshotComplete
    ) {}
}
