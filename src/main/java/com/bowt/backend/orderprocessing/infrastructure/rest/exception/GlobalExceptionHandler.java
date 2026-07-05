package com.bowt.backend.orderprocessing.infrastructure.rest.exception;

import com.bowt.backend.orderprocessing.domain.exception.InsufficientInventoryException;
import com.bowt.backend.orderprocessing.domain.exception.InvalidOrderStateException;
import com.bowt.backend.orderprocessing.domain.exception.PaymentFailedException;
import com.bowt.backend.orderprocessing.domain.exception.ProductNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * API-2: RFC 7807 ProblemDetail for every error type.
 * <p>
 * [I-3 fix] handleOrderState previously built a ProblemDetail with status=CONFLICT (409)
 * in the body but returned it via ResponseEntity.unprocessableEntity() — wire status 422.
 * Clients reading the HTTP status code (as most HTTP clients do by default) saw 422 while
 * the JSON body said 409. Now both agree: CONFLICT everywhere for this exception.
 * <p>
 * [I-4 fix] handleGeneric had the same class of bug: body said 500 (INTERNAL_SERVER_ERROR),
 * wire status was 422 (via unprocessableEntity()). Both copy-paste errors are fixed here by
 * deriving the ResponseEntity status directly FROM the ProblemDetail status in every handler,
 * so it is structurally impossible for the two to diverge again.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        ProblemDetail problem = baseProblem(HttpStatus.BAD_REQUEST, "invalid-request",
                "Request Validation Failed", "One or more fields failed validation", req);
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        problem.setProperty("errors", errors);
        return respond(problem);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest req) {
        ProblemDetail problem = baseProblem(HttpStatus.BAD_REQUEST, "invalid-request",
                "Missing Required Header", ex.getMessage(), req);
        return respond(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
        ProblemDetail problem = baseProblem(HttpStatus.BAD_REQUEST, "invalid-request",
                "Invalid Request", ex.getMessage(), req);
        return respond(problem);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleProductNotFound(ProductNotFoundException ex, HttpServletRequest req) {
        ProblemDetail problem = baseProblem(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-request",
                "Unknown Product", ex.getMessage(), req);
        return respond(problem);
    }

    @ExceptionHandler(InsufficientInventoryException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientInventory(InsufficientInventoryException ex, HttpServletRequest req) {
        ProblemDetail problem = baseProblem(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-inventory",
                "Insufficient Inventory", ex.getMessage(), req);
        return respond(problem);
    }

    @ExceptionHandler(PaymentFailedException.class)
    public ResponseEntity<ProblemDetail> handlePaymentFailed(PaymentFailedException ex, HttpServletRequest req) {
        ProblemDetail problem = baseProblem(HttpStatus.UNPROCESSABLE_ENTITY, "payment-failed",
                "Payment Failed", ex.getMessage(), req);
        return respond(problem);
    }

    /**
     * [I-3 fix] Wire status now matches the body status: 409, not 422.
     */
    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<ProblemDetail> handleOrderState(InvalidOrderStateException ex, HttpServletRequest req) {
        ProblemDetail problem = baseProblem(HttpStatus.CONFLICT, "invalid-order-state",
                "Invalid Order State Transition", ex.getMessage(), req);
        return respond(problem);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String type = status == HttpStatus.CONFLICT ? "idempotency-key-conflict" : "request-error";
        ProblemDetail problem = baseProblem(status, type, status.getReasonPhrase(),
                ex.getReason() != null ? ex.getReason() : status.getReasonPhrase(), req);
        return respond(problem);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NoSuchElementException ex, HttpServletRequest req) {
        ProblemDetail problem = baseProblem(HttpStatus.NOT_FOUND, "resource-not-found",
                "Resource Not Found", ex.getMessage() != null ? ex.getMessage() : "The requested resource was not found", req);
        return respond(problem);
    }

    /**
     * [I-4 fix] Wire status now matches the body status: 500, not 422.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ProblemDetail> handleGeneric(RuntimeException ex, HttpServletRequest req) {
        ProblemDetail problem = baseProblem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-server-error",
                "Unexpected Error", "An unexpected error occurred while processing the request", req);
        return respond(problem);
    }

    private ProblemDetail baseProblem(HttpStatus status, String typeSlug, String title, String detail,
                                      HttpServletRequest req) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://api.example.com/errors/" + typeSlug));
        problem.setTitle(title);
        problem.setInstance(URI.create(req.getRequestURI()));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    private Map<String, String> toFieldError(FieldError fe) {
        return Map.of("field", fe.getField(), "message", fe.getDefaultMessage());
    }

    /**
     * Single point of truth: the ResponseEntity status is always derived FROM the
     * ProblemDetail's own status field. This is what prevents the I-3/I-4 class of bug
     * (body and wire status disagreeing) from being reintroduced by a future handler.
     */
    private ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}