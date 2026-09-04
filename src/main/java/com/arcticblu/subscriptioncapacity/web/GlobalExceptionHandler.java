package com.arcticblu.subscriptioncapacity.web;

import com.arcticblu.subscriptioncapacity.algorithm.ProblemTooLargeException;
import com.arcticblu.subscriptioncapacity.service.InvalidSubscriptionInputException;
import com.arcticblu.subscriptioncapacity.service.OptimizationRunNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Translates exceptions into RFC 9457 problem responses.
 *
 * <p>Every failure carries a {@code type} URI identifying the class of problem and a
 * human-readable {@code detail}, so a client can tell a malformed payload from a
 * missing run without parsing prose.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} is what keeps Spring's own
 * dispatch failures out of the {@link Exception} catch-all below. An unknown path, an
 * unsupported method and an unsupported content type are all raised as exceptions by
 * the dispatcher; handled generically they would each become a 500 with a full stack
 * trace logged at ERROR, which turns an unauthenticated request loop into a
 * log-flooding primitive. The base class already answers them with the correct status
 * and an RFC 9457 body, and logs nothing at ERROR, so those cases are simply inherited.
 *
 * <p>The consequence is that any exception the base class declares must be handled by
 * overriding its {@code protected} method rather than by adding a second
 * {@code @ExceptionHandler} for the same type, which Spring would reject at startup as
 * an ambiguous mapping.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final URI TYPE_VALIDATION_FAILED =
            URI.create("https://arcticblu.example/problems/validation-failed");
    private static final URI TYPE_MALFORMED_REQUEST =
            URI.create("https://arcticblu.example/problems/malformed-request");
    private static final URI TYPE_PROBLEM_TOO_LARGE =
            URI.create("https://arcticblu.example/problems/problem-too-large");
    private static final URI TYPE_RUN_NOT_FOUND =
            URI.create("https://arcticblu.example/problems/run-not-found");
    private static final URI TYPE_CARRIED_FORWARD_CONFLICT =
            URI.create("https://arcticblu.example/problems/carried-forward-conflict");
    private static final URI TYPE_INTERNAL_ERROR =
            URI.create("https://arcticblu.example/problems/internal-error");

    /** Bean Validation failures on the request body, reported field by field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::describeFieldError)
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The request contains %d invalid field(s)".formatted(errors.size()));
        problem.setTitle("Validation failed");
        problem.setType(TYPE_VALIDATION_FAILED);
        problem.setProperty("errors", errors);

        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private Map<String, String> describeFieldError(FieldError error) {
        return Map.of(
                "field", error.getField(),
                "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage());
    }

    /** Body that is not valid JSON, or whose types cannot be bound. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The request body could not be parsed as JSON matching the expected schema");
        problem.setTitle("Malformed request body");
        problem.setType(TYPE_MALFORMED_REQUEST);

        return handleExceptionInternal(exception, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * A path variable or query parameter that cannot be converted, such as a bad UUID.
     *
     * <p>Registered as its own handler rather than as an override of the base class's
     * {@code handleTypeMismatch}: this subtype is the only mismatch the API can produce,
     * and it is the more specific mapping, so Spring prefers it.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "'%s' is not a valid value for %s".formatted(exception.getValue(), exception.getName()));
        problem.setTitle("Malformed request");
        problem.setType(TYPE_MALFORMED_REQUEST);
        return problem;
    }

    /** Input that passed structural validation but cannot be processed. */
    @ExceptionHandler(InvalidSubscriptionInputException.class)
    public ProblemDetail handleInvalidInput(InvalidSubscriptionInputException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid subscription input");
        problem.setType(TYPE_VALIDATION_FAILED);
        return problem;
    }

    /** A problem whose exact solution exceeds the solver's configured limits. */
    @ExceptionHandler(ProblemTooLargeException.class)
    public ProblemDetail handleProblemTooLarge(ProblemTooLargeException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Problem too large");
        problem.setType(TYPE_PROBLEM_TOO_LARGE);
        return problem;
    }

    @ExceptionHandler(OptimizationRunNotFoundException.class)
    public ProblemDetail handleRunNotFound(OptimizationRunNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Optimization run not found");
        problem.setType(TYPE_RUN_NOT_FOUND);
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleCarriedForwardConflict(DataIntegrityViolationException exception) throws Exception {
        if (exception.getMessage() != null && exception.getMessage().contains("uq_subscription_request_carried_from")) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "A carried-forward candidate in this request was claimed by a concurrent "
                            + "run. Retry the request.");
            problem.setTitle("Carried-forward candidate claimed concurrently");
            problem.setType(TYPE_CARRIED_FORWARD_CONFLICT);
            return problem;
        }
        throw exception; // Let other constraint violations fall through
    }

    /**
     * Anything unanticipated. The message is deliberately generic: internal details
     * are logged for operators, not returned to callers.
     *
     * <p>This is now genuinely a last resort. Every exception the base class declares
     * is routed to a handler of its own before reaching here, so a stack trace at ERROR
     * means a real defect rather than a caller typing the wrong URL.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedFailure(Exception exception) {
        log.error("Unhandled exception while serving request", exception);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "The request could not be processed due to an internal error");
        problem.setTitle("Internal server error");
        problem.setType(TYPE_INTERNAL_ERROR);
        return problem;
    }
}
