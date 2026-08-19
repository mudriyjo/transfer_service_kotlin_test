package com.bank.transfer.controller

import com.bank.transfer.domain.AccountAccessDeniedException
import com.bank.transfer.domain.AccountNotFoundException
import com.bank.transfer.domain.AccountCurrencyMismatchException
import com.bank.transfer.domain.IdempotencyConflictException
import com.bank.transfer.domain.InactiveAccountException
import com.bank.transfer.domain.InsufficientFundsException
import com.bank.transfer.domain.InvalidTransferException
import com.bank.transfer.domain.InvalidTransferStateException
import com.bank.transfer.domain.TransferDomainException
import com.bank.transfer.domain.TransferNotFoundException
import com.bank.transfer.domain.dto.TransferApiError
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

@RestControllerAdvice
class TransferExceptionHandler(
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(TransferNotFoundException::class)
    fun notFound(
        error: TransferNotFoundException,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> =
        response(HttpStatus.NOT_FOUND, error.code, error.message, exchange)

    @ExceptionHandler(AccountNotFoundException::class)
    fun accountNotFound(
        error: AccountNotFoundException,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> =
        response(HttpStatus.NOT_FOUND, error.code, error.message, exchange)

    @ExceptionHandler(IdempotencyConflictException::class)
    fun conflict(
        error: IdempotencyConflictException,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> =
        response(HttpStatus.CONFLICT, error.code, error.message, exchange)

    @ExceptionHandler(InvalidTransferStateException::class)
    fun invalidState(
        error: InvalidTransferStateException,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> = response(
        HttpStatus.CONFLICT,
        "INVALID_TRANSFER_STATE",
        error.message ?: "Transfer state does not allow this operation",
        exchange,
    )

    @ExceptionHandler(
        InvalidTransferException::class,
        ServerWebInputException::class,
        IllegalArgumentException::class,
    )
    fun invalidRequest(
        error: Exception,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> {
        val domain = error as? TransferDomainException
        return response(
            HttpStatus.BAD_REQUEST,
            domain?.code ?: "INVALID_TRANSFER_REQUEST",
            error.message ?: "Invalid request",
            exchange,
        )
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun constraintViolation(
        error: ConstraintViolationException,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> {
        val details = error.constraintViolations
            .groupBy { violation -> violation.propertyPath.toString().substringAfterLast('.') }
            .mapValues { (_, violations) ->
                violations.map { violation -> violation.message }
                    .distinct()
                    .joinToString("; ")
            }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            TransferApiError(
                code = "INVALID_TRANSFER_REQUEST",
                message = "Request validation failed",
                timestamp = clock.instant(),
                status = HttpStatus.BAD_REQUEST.value(),
                method = exchange.request.method.name(),
                path = exchange.request.path.value(),
                requestId = exchange.request.id,
                details = details,
            ),
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidBody(
        error: MethodArgumentNotValidException,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> {
        val fields = error.bindingResult.fieldErrors.associate { field ->
            field.field to (field.defaultMessage ?: "invalid")
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            TransferApiError(
                code = "INVALID_TRANSFER_REQUEST",
                message = "Request validation failed",
                timestamp = clock.instant(),
                status = HttpStatus.BAD_REQUEST.value(),
                method = exchange.request.method.name(),
                path = exchange.request.path.value(),
                requestId = exchange.request.id,
                details = fields,
            ),
        )
    }

    @ExceptionHandler(WebExchangeBindException::class)
    fun invalidReactiveBody(
        error: WebExchangeBindException,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> {
        val fields = error.bindingResult.fieldErrors
            .groupBy { field -> field.field }
            .mapValues { (_, errors) ->
                errors.map { field -> field.defaultMessage ?: "invalid" }
                    .distinct()
                    .joinToString("; ")
            }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            TransferApiError(
                code = "INVALID_TRANSFER_REQUEST",
                message = "Request validation failed",
                timestamp = clock.instant(),
                status = HttpStatus.BAD_REQUEST.value(),
                method = exchange.request.method.name(),
                path = exchange.request.path.value(),
                requestId = exchange.request.id,
                details = fields,
            ),
        )
    }

    @ExceptionHandler(
        InactiveAccountException::class,
        AccountCurrencyMismatchException::class,
        InsufficientFundsException::class,
    )
    fun unprocessable(
        error: TransferDomainException,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> =
        response(HttpStatus.UNPROCESSABLE_ENTITY, error.code, error.message, exchange)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun databaseConstraint(
        error: DataIntegrityViolationException,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> {
        logger.info(
            "Database constraint rejected requestId={} path={} type={}",
            exchange.request.id,
            exchange.request.path.value(),
            error.javaClass.simpleName,
        )
        return response(
            HttpStatus.CONFLICT,
            "TRANSFER_CONSTRAINT_CONFLICT",
            "The request conflicts with the current transfer state",
            exchange,
        )
    }

    @ExceptionHandler(TransientDataAccessException::class)
    fun transientDatabaseFailure(
        error: TransientDataAccessException,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> {
        logger.warn(
            "Transient database failure requestId={} path={} type={}",
            exchange.request.id,
            exchange.request.path.value(),
            error.javaClass.simpleName,
        )
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", "1")
            .body(
                TransferApiError(
                    code = "TRANSFER_STORAGE_TEMPORARILY_UNAVAILABLE",
                    message = "Transfer storage is temporarily unavailable",
                    timestamp = clock.instant(),
                    status = HttpStatus.SERVICE_UNAVAILABLE.value(),
                    method = exchange.request.method.name(),
                    path = exchange.request.path.value(),
                    requestId = exchange.request.id,
                ),
            )
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun responseStatus(
        error: ResponseStatusException,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> {
        val status = HttpStatus.resolve(error.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
        val message = if (status.is5xxServerError) {
            "The transfer request could not be completed"
        } else {
            error.reason ?: status.reasonPhrase
        }
        return response(
            status,
            "HTTP_${status.value()}",
            message,
            exchange,
        )
    }

    @ExceptionHandler(AccountAccessDeniedException::class, AccessDeniedException::class)
    fun forbidden(
        error: RuntimeException,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> =
        response(
            HttpStatus.FORBIDDEN,
            (error as? TransferDomainException)?.code ?: "TRANSFER_ACCESS_DENIED",
            error.message ?: "Access denied",
            exchange,
        )

    @ExceptionHandler(Throwable::class)
    fun unexpected(
        error: Throwable,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> {
        logger.error("Unhandled transfer error requestId={}", exchange.request.id, error)
        return response(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "The transfer could not be processed",
            exchange,
        )
    }

    private fun response(
        status: HttpStatus,
        code: String,
        message: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<TransferApiError> = ResponseEntity.status(status).body(
        TransferApiError(
            code = code,
            message = message,
            timestamp = clock.instant(),
            status = status.value(),
            method = exchange.request.method.name(),
            path = exchange.request.path.value(),
            requestId = exchange.request.id,
        ),
    )
}
