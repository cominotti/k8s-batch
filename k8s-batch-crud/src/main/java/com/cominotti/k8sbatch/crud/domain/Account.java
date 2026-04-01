// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.NaturalId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;

/**
 * Aggregate root representing a financial account owned by a {@link Customer}.
 *
 * <p>The {@code accountId} field (UUID string) is the natural business key — it is the same value
 * referenced by {@code enriched_transactions.account_id} in the batch pipeline, enabling
 * correlation between CRUD-managed master data and batch-produced enrichment results.
 *
 * <p>This entity is a separate aggregate from Customer because it must be independently queryable
 * by {@code accountId} for batch correlation.
 */
@Entity
@Table(name = "accounts")
@EntityListeners(AuditingEntityListener.class)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "account_seq")
    @SequenceGenerator(name = "account_seq", sequenceName = "account_sequence", allocationSize = 50)
    private Long id;

    @Version
    private int version;

    @NaturalId
    @Column(name = "account_id", nullable = false, unique = true, length = 36)
    private String accountId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    /** Required by JPA — protected to prevent construction without required fields. */
    protected Account() {
    }

    /**
     * Creates an active account for the given customer.
     *
     * @param accountId UUID string identifying this account (natural business key)
     * @param customer  the owning customer
     * @param currency  ISO 4217 currency code (e.g. USD, EUR, GBP)
     */
    public Account(String accountId, Customer customer, String currency) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.customer = Objects.requireNonNull(customer, "customer must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.status = AccountStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public int getVersion() {
        return version;
    }

    public String getAccountId() {
        return accountId;
    }

    public Customer getCustomer() {
        return customer;
    }

    void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    /**
     * Transitions the account to a new lifecycle status, enforcing the allowed state machine.
     *
     * @param target the desired new status
     * @throws IllegalStateException if the transition is not allowed
     */
    public void transitionTo(AccountStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Cannot transition account from " + status + " to " + target);
        }
        this.status = target;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Account other
                && Objects.equals(accountId, other.accountId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(accountId);
    }

    @Override
    public String toString() {
        return "Account{id=" + id + ", accountId='" + accountId + "', currency='" + currency
                + "', status=" + status + "}";
    }
}
