package graduation_project_be.shared.infrastructure.error;


import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import graduation_project_be.shared.application.exception.*;
import graduation_project_be.shared.domain.exception.InvalidBusinessRuleException;
import graduation_project_be.shared.domain.models.enums.Status;
import graduation_project_be.user.domain.enums.Role;
import graduation_project_be.shared.infrastructure.error.exceptions.JwtInvalidException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;

import static graduation_project_be.shared.infrastructure.error.ErrorCode.*;


@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({ DataIntegrityViolationException.class, JpaSystemException.class, SQLIntegrityConstraintViolationException.class })
    public ResponseEntity<ErrorResponse> handleJpaExceptions(Exception ex) {
        logger.error("Database error", ex);

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(ErrorCode.DATABASE_ERROR)
                .message("A database error occurred. Please try again later.")
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    // @ExceptionHandler(FileParsingException.class) ... -> Removed as file does not exist

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException e) {
        logger.error("Validation error occurred", e);

        var list = new ArrayList<FieldErrorDetail>();
        e.getBindingResult().getFieldErrors().forEach(
            fieldError -> list.add(new FieldErrorDetail(fieldError.getField(), fieldError.getDefaultMessage()))
        );
        ErrorResponse error = ErrorResponse.builder()
            .errorCode(VALIDATION_FAILED)
            .message("Invalid input(s)")
            .details(list)
            .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception e) {
        logger.error("Unhandled exception occurred", e);

        ErrorResponse error = ErrorResponse.builder()
            .errorCode(INTERNAL_SERVER_ERROR)
            .message(e.getMessage())
            .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }


    @ExceptionHandler(JwtInvalidException.class)
    public ResponseEntity<ErrorResponse> handleJwtInvalid(JwtInvalidException e) {
        logger.error("JWT token is invalid", e);

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(JWT_TOKEN_INVALID)
                .message(e.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException invalidFormatException) {
            Class<?> targetType = invalidFormatException.getTargetType();
            if (targetType.equals(Status.class)) {
                ErrorResponse error = ErrorResponse.builder()
                    .errorCode(STATUS_NOT_FOUND)
                    .message("Invalid status. Valid values are: [UNVERIFIED, ACTIVE, INACTIVE]")
                    .build();
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            } else if (targetType.equals(Role.class)) {
                ErrorResponse error = ErrorResponse.builder()
                    .errorCode(ROLE_NOT_FOUND)
                    .message("Invalid role. Valid values are: [ADMIN, USER]")
                    .build();
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }
        }

        logger.error("HTTP message not readable exception", ex);

        ErrorResponse error = ErrorResponse.builder()
            .errorCode(VALIDATION_FAILED)
            .message("Invalid value in request")
            .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        logger.error("Method argument type mismatch exception", ex);

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(VALIDATION_FAILED)
                .message("Invalid input(s)")
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }



    @ExceptionHandler(InvalidFormatException.class)
    public  ResponseEntity<ErrorResponse> handleInvalidFormatException(InvalidFormatException ex) {
        logger.error("Invalid format exception", ex);

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(INVALID_FORMAT)
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(InvalidBusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBusinessRuleException(InvalidBusinessRuleException ex) {
        logger.error("Invalid business rule exception", ex);

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(INVALID_BUSINESS_RULE)
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequestException(BadRequestException ex) {
        logger.error("Bad request exception", ex);

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(BAD_REQUEST)
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflictException(ConflictException ex) {
        logger.error("Conflict exception", ex);

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(CONFLICT)
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFoundException(ResourceNotFoundException ex) {
        logger.error("Resource not found exception", ex);

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(NOT_FOUND)
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    @ExceptionHandler(OperationFailedException.class)
    public ResponseEntity<ErrorResponse> handleOperationFailedException(OperationFailedException ex) {
        logger.error("Operation Failed Exception", ex);

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(OPERATION_FAILED)
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedException(UnauthorizedException ex) {
        logger.error("Unauthorized exception", ex);

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(UNAUTHORIZED)
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(ApplicationException ex) {
        logger.error("Application exception", ex);

        ErrorResponse error = ErrorResponse.builder()
                .errorCode(INTERNAL_ERROR)
                .message(ex.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e){
        ErrorResponse error = ErrorResponse.builder()
                .errorCode(UNAUTHORIZED)
                .message(e.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }
}