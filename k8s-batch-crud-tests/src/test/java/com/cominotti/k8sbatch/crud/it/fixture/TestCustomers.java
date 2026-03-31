// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.it.fixture;

import com.cominotti.k8sbatch.crud.domain.Customer;

/**
 * Factory methods for creating test {@link Customer} instances.
 */
public final class TestCustomers {

    /**
     * Creates a default test customer.
     *
     * @return a new customer with name "John Doe" and email "john@example.com"
     */
    public static Customer aCustomer() {
        return new Customer("John Doe", "john@example.com");
    }

    /**
     * Creates a test customer with the given name and email.
     *
     * @param name  display name
     * @param email email address
     * @return a new customer
     */
    public static Customer aCustomer(String name, String email) {
        return new Customer(name, email);
    }

    private TestCustomers() {
    }
}
