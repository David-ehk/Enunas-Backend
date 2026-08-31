package com.enunas.backend.exception;

// tools.jackson, not com.fasterxml.jackson: Spring Boot 4 / Spring Framework 7 moved the default
// JSON message converter to Jackson 3, which relocated its package root. com.fasterxml.jackson.*
// classes still resolve on the classpath (jjwt-jackson pulls Jackson 2 transitively) but are never
// the actual runtime type Spring's converter throws — an easy silent-mismatch trap.
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for the HTTP error shape.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} rather than listing Spring MVC's exceptions by
 * hand. That base class already declares an {@code @ExceptionHandler} for the whole MVC family —
 * missing request parameters, unsupported/unacceptable media types, missing multipart parts,
 * request-binding failures, method-level validation, async timeouts, upload-size overruns and
 * {@code ErrorResponse} exceptions — and maps each to its correct status <em>with</em> the response
 * headers the RFCs require ({@code Allow} on 405, {@code Accept} on 415). Anything not listed here
 * by hand previously fell through to the {@code Exception.class} catch-all as a 500; the most
 * visible case was the public {@code GET /products/search} answering 500 instead of 400 when
 * {@code ?keyword=} was omitted.
 *
 * <p>The base class emits RFC 9457 {@code ProblemDetail} bodies. {@link #handleExceptionInternal}
 * is overridden to re-shape every one of them into this API's existing
 * {@code {timestamp, status, error, message, path}} envelope, so adopting the base class changes
 * statuses and headers but never the body contract.
 *
 * <p>Handlers declared directly on this class win over the inherited ones by
 * {@code @ExceptionHandler} specificity, so the domain exceptions and the catch-all keep their
 * meaning.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // ------------------------------------------------------------------------------------------
    // Application / domain exceptions
    // ------------------------------------------------------------------------------------------

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("IllegalArgumentException: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * Also covers {@code MultipleReturnsException}, which deliberately extends
     * {@link IllegalStateException} so the order-scoped return endpoints answer 409 without needing
     * a dedicated handler here.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(
            IllegalStateException ex, HttpServletRequest request) {
        log.warn("IllegalStateException: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler({OrderNotFoundException.class, BrandNotFoundException.class,
            ProductNotFoundException.class, CustomerNotFoundException.class, AddressNotFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(
            RuntimeException ex, HttpServletRequest request) {
        log.warn("NotFoundException: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<Map<String, Object>> handlePayment(
            PaymentException ex, HttpServletRequest request) {
        log.warn("PaymentException: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurity(
            SecurityException ex, HttpServletRequest request) {
        log.warn("SecurityException: {}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage(), request);
    }

    // Spring Security's own AccessDeniedException — thrown by @PreAuthorize denials (via the newer
    // AuthorizationDeniedException, which extends this) — is a completely different type from the
    // JDK's java.lang.SecurityException handled above and was falling through to the generic
    // Exception.class handler as 500. Same class of bug as the routing exceptions below: an
    // authenticated-but-wrong-role caller hitting an admin-only route got "unexpected error"
    // instead of a clean 403.
    //
    // NOTE: this covers denials raised *inside* the DispatcherServlet (method security only).
    // URL-rule denials from authorizeHttpRequests are raised in the filter chain and answered by
    // Spring Security's ExceptionTranslationFilter, which never reaches @ControllerAdvice.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        log.warn("AccessDeniedException: {}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "Access denied", request);
    }

    @ExceptionHandler(PeriodNotClosedException.class)
    public ResponseEntity<Map<String, Object>> handlePeriodNotClosed(
            PeriodNotClosedException ex, HttpServletRequest request) {
        log.warn("PeriodNotClosedException: {}", ex.getMessage());
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request);
    }

    @ExceptionHandler(JwtException.class)
    public ResponseEntity<Map<String, Object>> handleJwt(
            JwtException ex, HttpServletRequest request) {
        log.warn("JwtException: type={}, message={}", ex.getClass().getSimpleName(), ex.getMessage());
        return buildResponse(HttpStatus.UNAUTHORIZED,
                "Token is invalid or expired — please log in again", request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest request) {
        log.warn("BadCredentialsException: {}", ex.getMessage());
        return buildResponse(HttpStatus.UNAUTHORIZED, "Invalid email or password", request);
    }

    /**
     * The rest of Spring Security's AuthenticationException tree — DisabledException,
     * LockedException, CredentialsExpiredException, and the InsufficientAuthenticationException
     * that SecurityConfiguration's entry point forwards here for anonymous callers. None of them
     * had a handler, so each surfaced as a bare 500; see the comment on
     * {@code ApplicationConfiguration.authenticationProvider()}, which disables
     * DaoAuthenticationProvider's pre-authentication checks partly to dodge that.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(
            AuthenticationException ex, HttpServletRequest request) {
        log.warn("AuthenticationException: type={}, message={}",
                ex.getClass().getSimpleName(), ex.getMessage());
        return buildResponse(HttpStatus.UNAUTHORIZED, "Authentication required", request);
    }

    /**
     * The one AuthenticationException that is not the caller's fault: it wraps a failure inside the
     * UserDetailsService (a dead database, say). Answering 401 would tell the caller to re-enter
     * credentials that were never the problem, and would hide an outage behind a client error.
     */
    @ExceptionHandler(InternalAuthenticationServiceException.class)
    public ResponseEntity<Map<String, Object>> handleInternalAuthenticationService(
            InternalAuthenticationServiceException ex, HttpServletRequest request) {
        log.error("InternalAuthenticationServiceException: {}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("DataIntegrityViolationException: {}", ex.getMostSpecificCause().getMessage());
        return buildResponse(HttpStatus.CONFLICT, "Data integrity violation", request);
    }

    // Order, Payment and BrandEconomics are @Version-mapped, so two concurrent writers lose the
    // race with ObjectOptimisticLockingFailureException. That is a retryable conflict the caller
    // can act on, not the opaque "unexpected error" 500 the catch-all was reporting.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLocking(
            OptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("OptimisticLockingFailureException: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT,
                "The record was modified concurrently — please reload and retry", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception: type={}, message={}, cause={}",
                ex.getClass().getSimpleName(),
                ex.getMessage(),
                ex.getCause() != null ? ex.getCause().getMessage() : "none",
                ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    // ------------------------------------------------------------------------------------------
    // Spring MVC exceptions — overrides of ResponseEntityExceptionHandler.
    //
    // These must be overrides, not new @ExceptionHandler methods: the base class already declares
    // these exact types, and a second mapping for the same type fails context startup with
    // "Ambiguous @ExceptionHandler method mapped". Every MVC exception NOT overridden here still
    // gets the base class's correct status and headers, re-shaped by handleExceptionInternal.
    // ------------------------------------------------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        log.warn("NoResourceFoundException: {}", ex.getMessage());
        return handleExceptionInternal(ex,
                "No endpoint " + ex.getHttpMethod() + " " + ex.getResourcePath(),
                headers, status, request);
    }

    /**
     * A parameter that fails to convert (a non-numeric id on a {@code Long} {@code {id}}, an
     * unknown constant on an enum-typed {@code ?status=}) is a malformed request, not a missing
     * resource: 404 stays reserved for a syntactically valid id that just doesn't match a record.
     *
     * <p>Covers {@link MethodArgumentTypeMismatchException} as well — it extends
     * {@link TypeMismatchException}, so the base class routes it here.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName() : null;
        String message = describeBadValue(ex.getValue(), name, ex.getRequiredType());
        log.warn("TypeMismatchException: {}", message);
        return handleExceptionInternal(ex, message, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        log.warn("ValidationException: {}", message);
        return handleExceptionInternal(ex, message, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = "Malformed or invalid request body";
        if (ex.getCause() instanceof UnrecognizedPropertyException upe) {
            message = "Unknown field: " + upe.getPropertyName();
        } else if (ex.getCause() instanceof InvalidFormatException ife) {
            String field = ife.getPath().isEmpty()
                    ? null
                    : ife.getPath().get(ife.getPath().size() - 1).getPropertyName();
            message = describeBadValue(ife.getValue(), field, ife.getTargetType());
        }
        log.warn("HttpMessageNotReadableException: {}", message);
        return handleExceptionInternal(ex, message, headers, status, request);
    }

    /**
     * Every response produced by {@link ResponseEntityExceptionHandler} funnels through here, so
     * this is where the base class's {@code ProblemDetail} body is replaced by this API's envelope.
     *
     * @param body a {@link String} when one of the overrides above supplied a hand-written message,
     *             the base class's {@link ProblemDetail} otherwise, or {@code null} when the
     *             exception carries its own {@link ErrorResponse} body
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        // Mirrors the base class: once bytes are on the wire there is no body left to replace, and
        // writing one would corrupt the response.
        if (request instanceof ServletWebRequest servletWebRequest) {
            HttpServletResponse response = servletWebRequest.getResponse();
            if (response != null && response.isCommitted()) {
                log.warn("Response already committed, ignoring: {}", ex.toString());
                return null;
            }
        }

        String message = resolveMessage(ex, body, status);
        if (status.is5xxServerError()) {
            log.error("Unhandled MVC exception: type={}, message={}",
                    ex.getClass().getSimpleName(), ex.getMessage(), ex);
            // Internal failures (unwritable response, unsupported conversion) must not leak their
            // detail to the caller — same contract as the Exception.class catch-all above.
            message = "An unexpected error occurred";
        }

        return ResponseEntity.status(status).headers(headers)
                .body(errorBody(status, message, pathOf(request)));
    }

    private String resolveMessage(Exception ex, Object body, HttpStatusCode status) {
        if (body instanceof String handWritten) {
            return handWritten;
        }
        ProblemDetail detail = null;
        if (body instanceof ProblemDetail problemDetail) {
            detail = problemDetail;
        } else if (ex instanceof ErrorResponse errorResponse) {
            // The base class leaves body null for these and lets the exception supply its own
            // RFC 9457 body; resolve it the same way so the detail text is not lost.
            detail = errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (detail != null && detail.getDetail() != null) {
            return detail.getDetail();
        }
        return ex.getMessage() != null ? ex.getMessage() : reasonPhrase(status);
    }

    // ------------------------------------------------------------------------------------------
    // Shared shape
    // ------------------------------------------------------------------------------------------

    /**
     * One phrasing for every rejected value, so a bad enum reads the same whether it arrived in a
     * path variable, a query parameter or a JSON field.
     */
    private String describeBadValue(Object value, String name, Class<?> targetType) {
        String where = name != null ? " for '" + name + "'" : "";
        if (targetType != null && targetType.isEnum()) {
            return "Invalid value '" + value + "'" + where
                    + ". Allowed values: " + Arrays.toString(targetType.getEnumConstants());
        }
        return "Invalid value '" + value + "'" + where;
    }

    private ResponseEntity<Map<String, Object>> buildResponse(
            HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(errorBody(status, message, request != null ? request.getRequestURI() : null));
    }

    private Map<String, Object> errorBody(HttpStatusCode status, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", reasonPhrase(status));
        // ex.getMessage() is null for exceptions constructed without one; the reason phrase is a
        // truthful fallback and keeps "message" a String for every consumer.
        body.put("message", message != null ? message : reasonPhrase(status));
        body.put("path", path);
        return body;
    }

    private String reasonPhrase(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved != null ? resolved.getReasonPhrase() : "Error";
    }

    private String pathOf(WebRequest request) {
        return request instanceof ServletWebRequest servletWebRequest
                ? servletWebRequest.getRequest().getRequestURI()
                : null;
    }
}
