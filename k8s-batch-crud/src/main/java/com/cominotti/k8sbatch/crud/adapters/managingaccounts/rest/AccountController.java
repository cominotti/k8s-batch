// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.managingaccounts.rest;

import com.cominotti.k8sbatch.crud.adapters.managingaccounts.rest.dto.AccountResponse;
import com.cominotti.k8sbatch.crud.adapters.managingaccounts.rest.dto.CreateAccountRequest;
import com.cominotti.k8sbatch.crud.adapters.managingaccounts.rest.dto.UpdateAccountStatusRequest;
import com.cominotti.k8sbatch.crud.domain.Account;
import com.cominotti.k8sbatch.crud.domain.AccountService;
import com.cominotti.k8sbatch.crud.domain.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * REST controller for Account CRUD operations (driving adapter).
 *
 * <p>Supports lookup by the natural business key ({@code accountId}) for correlation with
 * the batch pipeline's {@code enriched_transactions.account_id}.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    /**
     * Creates the controller with the account application service.
     *
     * @param accountService application service for account operations
     */
    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Creates a new account. Returns HTTP 201 with a Location header.
     *
     * @param request the account data
     * @return HTTP 201 with the created account
     */
    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(
                request.customerId(), request.accountId(), request.currency());
        AccountResponse response = AccountResponse.from(account);
        URI location = URI.create("/api/accounts/" + account.getId());
        return ResponseEntity.created(location).body(response);
    }

    /**
     * Retrieves an account by surrogate ID.
     *
     * @param id the account surrogate ID
     * @return HTTP 200 with the account, or HTTP 404 if not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable Long id) {
        return accountService.findById(id)
                .map(AccountResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new EntityNotFoundException("Account", id));
    }

    /**
     * Lists accounts filtered by business key or customer ownership.
     *
     * @param accountId  optional natural business key filter
     * @param customerId optional customer ownership filter
     * @return HTTP 200 with matching accounts
     */
    @GetMapping
    public ResponseEntity<?> listAccounts(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) Long customerId) {
        if (accountId != null) {
            return accountService.findByAccountId(accountId)
                    .map(AccountResponse::from)
                    .map(ResponseEntity::ok)
                    .orElseThrow(() -> new EntityNotFoundException("Account", accountId));
        }
        if (customerId != null) {
            List<AccountResponse> accounts = accountService.findByCustomerId(customerId).stream()
                    .map(AccountResponse::from)
                    .toList();
            return ResponseEntity.ok(accounts);
        }
        return ResponseEntity.badRequest().body("At least one filter (accountId or customerId) is required");
    }

    /**
     * Updates the lifecycle status of an existing account.
     *
     * @param id      the account surrogate ID
     * @param request the new status
     * @return HTTP 200 with the updated account
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<AccountResponse> updateAccountStatus(
            @PathVariable Long id, @Valid @RequestBody UpdateAccountStatusRequest request) {
        Account account = accountService.updateAccountStatus(id, request.status());
        return ResponseEntity.ok(AccountResponse.from(account));
    }

    /**
     * Deletes an account.
     *
     * @param id the account surrogate ID
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAccount(@PathVariable Long id) {
        accountService.deleteAccount(id);
        return ResponseEntity.noContent().build();
    }
}
