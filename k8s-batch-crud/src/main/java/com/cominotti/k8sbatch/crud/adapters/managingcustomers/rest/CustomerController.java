// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest;

import com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto.CreateCustomerRequest;
import com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto.CustomerResponse;
import com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto.CustomerWithAccountsResponse;
import com.cominotti.k8sbatch.crud.adapters.managingcustomers.rest.dto.UpdateCustomerRequest;
import com.cominotti.k8sbatch.crud.domain.Customer;
import com.cominotti.k8sbatch.crud.domain.CustomerService;
import com.cominotti.k8sbatch.crud.domain.EntityNotFoundException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

/**
 * REST controller for Customer CRUD operations (driving adapter).
 *
 * <p>Follows CQS: GET endpoints are queries (no side effects), POST/PUT/DELETE are commands.
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    /**
     * Creates the controller with the customer application service.
     *
     * @param customerService application service for customer operations
     */
    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    /**
     * Creates a new customer. Returns HTTP 201 with a Location header pointing to the new resource.
     *
     * @param request the customer data
     * @return HTTP 201 with the created customer
     */
    @PostMapping
    public ResponseEntity<CustomerResponse> createCustomer(@Valid @RequestBody CreateCustomerRequest request) {
        Customer customer = customerService.createCustomer(request.name(), request.email());
        CustomerResponse response = CustomerResponse.from(customer);
        URI location = URI.create("/api/customers/" + customer.getId());
        return ResponseEntity.created(location).body(response);
    }

    /**
     * Retrieves a customer by surrogate ID.
     *
     * @param id the customer ID
     * @return HTTP 200 with the customer, or HTTP 404 if not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable Long id) {
        return customerService.findById(id)
                .map(CustomerResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new EntityNotFoundException("Customer", id));
    }

    /**
     * Retrieves a customer by email (natural business key), or returns a paginated list if no
     * email is specified.
     *
     * @param email    optional email filter
     * @param pageable pagination parameters (from query string: page, size, sort)
     * @return HTTP 200 with matching customers
     */
    @GetMapping
    public ResponseEntity<?> listOrFindCustomers(
            @RequestParam(required = false) String email, Pageable pageable) {
        if (email != null) {
            return customerService.findByEmail(email)
                    .map(CustomerResponse::from)
                    .map(ResponseEntity::ok)
                    .orElseThrow(() -> new EntityNotFoundException("Customer", email));
        }
        Page<CustomerResponse> page = customerService.findAll(pageable).map(CustomerResponse::from);
        return ResponseEntity.ok(page);
    }

    /**
     * Retrieves a customer with their accounts eagerly loaded (avoids N+1).
     *
     * @param id the customer ID
     * @return HTTP 200 with the customer and nested accounts
     */
    @GetMapping("/{id}/accounts")
    public ResponseEntity<CustomerWithAccountsResponse> getCustomerWithAccounts(@PathVariable Long id) {
        return customerService.findWithAccounts(id)
                .map(CustomerWithAccountsResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new EntityNotFoundException("Customer", id));
    }

    /**
     * Updates the name and status of an existing customer.
     *
     * @param id      the customer ID
     * @param request the updated data
     * @return HTTP 200 with the updated customer
     */
    @PutMapping("/{id}")
    public ResponseEntity<CustomerResponse> updateCustomer(
            @PathVariable Long id, @Valid @RequestBody UpdateCustomerRequest request) {
        Customer customer = customerService.updateCustomer(id, request.name(), request.status());
        return ResponseEntity.ok(CustomerResponse.from(customer));
    }

    /**
     * Deletes a customer.
     *
     * @param id the customer ID
     * @return HTTP 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCustomer(@PathVariable Long id) {
        customerService.deleteCustomer(id);
        return ResponseEntity.noContent().build();
    }
}
