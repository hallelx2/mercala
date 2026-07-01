package com.mercala.identity.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.mercala.identity.exception.InvalidCredentialsException;
import com.mercala.identity.exception.ResourceConflictException;
import com.mercala.identity.exception.ResourceNotFoundException;

/**
 * Translates domain + validation errors into RFC 7807 {@link ProblemDetail} responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setTitle("Bad Request");

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        problem.setProperty("errors", fieldErrors);
        return problem;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Unauthorized");
        return problem;
    }

    @ExceptionHandler(ResourceConflictException.class)
    public ProblemDetail handleConflict(ResourceConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflict");
        return problem;
    }

    /**
     * Backstop for the race between the exists-check and the insert: if two concurrent
     * requests both pass the check, the database unique constraint still rejects the second
     * one — translate that into a 409 instead of a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The request conflicts with an existing resource.");
        problem.setTitle("Conflict");
        return problem;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Not Found");
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        log.warn("IllegalStateException while processing request", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The request could not be processed due to an invalid state. Please verify the request status and try again."
        );
        problem.setTitle("Bad Request");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("IllegalArgumentException while processing request", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The request contains invalid parameters or input values. Please verify your data and try again."
        );
        problem.setTitle("Bad Request");
        return problem;
    }
}
