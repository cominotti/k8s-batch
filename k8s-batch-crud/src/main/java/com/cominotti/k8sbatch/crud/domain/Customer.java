// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.NaturalId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Aggregate root representing a customer who owns one or more {@link Account}s.
 *
 * <p>The {@code email} field is the natural business key — used in {@code equals}/{@code hashCode}
 * and as the unique identifier for external lookups. The surrogate {@code id} is for internal
 * database use and foreign key references.
 *
 * <p>Optimistic locking via {@code @Version} prevents concurrent updates from silently overwriting
 * each other.
 */
@Entity
@Table(name = "customers")
@EntityListeners(AuditingEntityListener.class)
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "customer_seq")
    @SequenceGenerator(name = "customer_seq", sequenceName = "customer_sequence", allocationSize = 50)
    private Long id;

    @Version
    private int version;

    @Column(nullable = false)
    private String name;

    @NaturalId
    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerStatus status;

    // CascadeType.REMOVE intentionally omitted — CustomerService.deleteCustomer() uses bulk
    // deleteByCustomerId() to avoid loading all accounts into memory before deleting them.
    @OneToMany(mappedBy = "customer", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private Set<Account> accounts = new HashSet<>();

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    /** Required by JPA — protected to prevent construction without required fields. */
    protected Customer() {
    }

    /**
     * Creates an active customer with the given name and email.
     *
     * @param name  display name
     * @param email unique email address (natural business key)
     */
    public Customer(String name, String email) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.email = Objects.requireNonNull(email, "email must not be null");
        this.status = CustomerStatus.ACTIVE;
    }

    /**
     * Adds an account to this customer, maintaining both sides of the bidirectional relationship.
     *
     * @param account the account to add
     */
    public void addAccount(Account account) {
        accounts.add(account);
        account.setCustomer(this);
    }

    /**
     * Removes an account from this customer, clearing the back-reference.
     *
     * @param account the account to remove
     */
    public void removeAccount(Account account) {
        accounts.remove(account);
        account.setCustomer(null);
    }

    public Long getId() {
        return id;
    }

    public int getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    /**
     * Updates the customer's display name.
     *
     * @param name the new name (must not be null or blank)
     * @throws NullPointerException if name is null
     */
    public void rename(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    /**
     * Transitions the customer to a new lifecycle status, enforcing the allowed state machine.
     *
     * @param target the desired new status
     * @throws IllegalStateException if the transition is not allowed
     */
    public void transitionTo(CustomerStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Cannot transition customer from " + status + " to " + target);
        }
        this.status = target;
    }

    /**
     * Returns an unmodifiable view of this customer's accounts. Use {@link #addAccount} and
     * {@link #removeAccount} to modify the relationship.
     *
     * @return unmodifiable set of accounts
     */
    public Set<Account> getAccounts() {
        return Collections.unmodifiableSet(accounts);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Customer other
                && Objects.equals(email, other.email);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(email);
    }

    @Override
    public String toString() {
        return "Customer{id=" + id + ", email='" + email + "', status=" + status + "}";
    }
}
