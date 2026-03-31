// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.adapters.handlingerrors.rest;

import com.cominotti.k8sbatch.crud.domain.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * Translates domain and infrastructure exceptions into standard HTTP problem responses.
 *
 * <p>Uses RFC 9457 Problem Detail format via Spring's {@code ProblemDetail}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Entity not found — HTTP 404.
     *
     * @param ex the exception
     * @return problem detail with status 404
     */
    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleNotFound(EntityNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * Optimistic lock conflict — HTTP 409. Indicates that another client modified the entity
     * between this client's read and write.
     *
     * @param ex the exception
     * @return problem detail with status 409
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict | entity={} | id={}",
                ex.getPersistentClassName(), ex.getIdentifier());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The resource was modified by another request. Please retry.");
        detail.setTitle("Concurrent Modification");
        return detail;
    }

    /**
     * Data integrity violation (duplicate key, FK violation, NOT NULL violation) — HTTP 409.
     *
     * @param ex the exception
     * @return problem detail with status 409
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation | message={}", ex.getMostSpecificCause().getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "Data integrity constraint violated.");
        detail.setTitle("Data Integrity Violation");
        return detail;
    }

    /**
     * Malformed request body (invalid JSON, wrong types, unknown enum values) — HTTP 400.
     * Prevents leaking internal deserialization details to clients.
     *
     * @param ex the exception
     * @return problem detail with status 400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformedRequest(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body | message={}", ex.getMostSpecificCause().getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body.");
    }

    /**
     * Bean validation failure — HTTP 400 with field-level error details.
     *
     * @param ex the exception
     * @return problem detail with status 400 and field errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", fe.getDefaultMessage()))
                .toList();
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Validation failed");
        detail.setTitle("Validation Error");
        detail.setProperty("errors", fieldErrors);
        return detail;
    }
}
