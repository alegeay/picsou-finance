package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.port.GroupamaEsErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupamaEsAdapterTest {

    @Test
    void fetchAccountsMapsTheStrictSidecarContract() {
        GroupamaEsAdapter adapter = adapterReturning(HttpStatus.OK, """
            [{
              "externalId":"ges_pee_42",
              "name":"PEE Groupama",
              "type":"PEE",
              "balanceEur":1250.50,
              "positions":[{
                "symbol":"GES_FCPE_1",
                "label":"Groupama Sélection ISR",
                "quantity":10,
                "currentPriceEur":125.05,
                "currentValueEur":1250.50,
                "pnlEur":200.50
              }],
              "snapshotComplete":true
            }]
            """);

        var accounts = adapter.fetchAccounts("browser-storage-state");

        assertThat(accounts).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("ges_pee_42");
            assertThat(account.type()).isEqualTo(AccountType.PEE);
            assertThat(account.balanceEur()).isEqualByComparingTo("1250.50");
            assertThat(account.snapshotComplete()).isTrue();
            assertThat(account.positions()).singleElement().satisfies(position -> {
                assertThat(position.symbol()).isEqualTo("GES_FCPE_1");
                assertThat(position.currentPriceEur()).isEqualByComparingTo("125.05");
                assertThat(position.currentValueEur()).isEqualByComparingTo("1250.50");
                assertThat(position.pnlEur()).isEqualByComparingTo("200.50");
            });
        });
    }

    @Test
    void fetchAccountsPreservesStableSidecarErrorCodes() {
        GroupamaEsAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY,
            "{\"detail\":\"PORTFOLIO_INCOMPLETE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode())
                    .isEqualTo(GroupamaEsErrorCode.PORTFOLIO_INCOMPLETE.name());
                assertThat(error.getMessage()).doesNotContain("state");
            });
    }

    @Test
    void initiateAuthPreservesActionRequired() {
        GroupamaEsAdapter adapter = adapterReturning(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "{\"detail\":\"ACTION_REQUIRED\"}"
        );

        assertThatThrownBy(() -> adapter.initiateAuth("login", "password"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode())
                    .isEqualTo(GroupamaEsErrorCode.ACTION_REQUIRED.name())
            );
    }

    @Test
    void completeAuthMapsAnUncoded401ToInvalidOtp() {
        GroupamaEsAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED,
            "{\"detail\":\"Authentication rejected\"}"
        );

        assertThatThrownBy(() -> adapter.completeAuth("process", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode())
                    .isEqualTo(GroupamaEsErrorCode.INVALID_OTP.name())
            );
    }

    @Test
    void initiateAuthMapsAnEmptySuccessResponseToUnavailable() {
        GroupamaEsAdapter adapter = adapterReturning(HttpStatus.OK, "");

        assertThatThrownBy(() -> adapter.initiateAuth("login", "password"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode())
                    .isEqualTo(GroupamaEsErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchAccountsMapsUnknownSidecarCodeToUnavailable() {
        GroupamaEsAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY,
            "{\"detail\":\"NEW_UPSTREAM_FAILURE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode())
                    .isEqualTo(GroupamaEsErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchAccountsMapsNetworkTimeoutToExplicitRetryableFailure() {
        ExchangeFunction neverResponds = request -> Mono.never();
        GroupamaEsAdapter adapter = new GroupamaEsAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(20)
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode())
                    .isEqualTo(GroupamaEsErrorCode.UPSTREAM_UNAVAILABLE.name());
                assertThat(error.getMessage()).contains("too long");
            });
    }

    private GroupamaEsAdapter adapterReturning(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
        return new GroupamaEsAdapter(
            WebClient.builder().exchangeFunction(exchange).build(),
            new ObjectMapper()
        );
    }
}
