package library.service.api

import library.service.business.exceptions.MalformedValueException
import library.service.business.exceptions.NotFoundException
import library.service.business.exceptions.NotPossibleException
import library.service.logging.logger
import org.springframework.beans.TypeMismatchException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatus.*
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Defines a number of commonly used exception handlers for REST endpoints.
 *
 * This includes basic handlers for common business exceptions like:
 * - [NotFoundException]
 * - [NotPossibleException]
 * - [MalformedValueException]
 *
 * As well as a number of framework exceptions related to bad user input.
 *
 * This class should _not_ contain any domain specific exception handlers.
 * Those need to be defined in the corresponding controller!
 */
@RestControllerAdvice
class ErrorHandlers(
    private val clock: Clock
) {

    private val log = ErrorHandlers::class.logger

    @ResponseStatus(NOT_FOUND)
    @ExceptionHandler(NotFoundException::class)
    fun handle(
        e: NotFoundException,
        exchange: ServerWebExchange
    ): Mono<ResponseEntity<ErrorDescription>> {
        log.debug("received request for non existing resource:", e)
        return errorDescription(
            exchange,
            httpStatus = NOT_FOUND,
            message = e.message!!
        )
    }

    @ResponseStatus(CONFLICT)
    @ExceptionHandler(NotPossibleException::class)
    fun handle(
        e: NotPossibleException,
        exchange: ServerWebExchange
    ): Mono<ResponseEntity<ErrorDescription>> {
        log.debug("received conflicting request:", e)
        return errorDescription(
            exchange,
            httpStatus = CONFLICT,
            message = e.message!!
        )
    }

    @ResponseStatus(BAD_REQUEST)
    @ExceptionHandler(MalformedValueException::class)
    fun handle(
        e: MalformedValueException,
        exchange: ServerWebExchange
    ): Mono<ResponseEntity<ErrorDescription>> {
        log.debug("received malformed request:", e)
        return errorDescription(
            exchange,
            httpStatus = BAD_REQUEST,
            message = e.message!!
        )
    }

    /** In case the request parameter has wrong type. */
    @ResponseStatus(BAD_REQUEST)
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handle(
        e: MethodArgumentTypeMismatchException,
        exchange: ServerWebExchange
    ): Mono<ResponseEntity<ErrorDescription>> {
        log.debug("received bad request:", e)
        return errorDescription(
            exchange,
            httpStatus = BAD_REQUEST,
            message = "The request's '${e.name}' parameter is malformed."
        )
    }

    /** In case the request body is malformed or non existing. */
    @ResponseStatus(BAD_REQUEST)
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handle(
        e: HttpMessageNotReadableException,
        exchange: ServerWebExchange
    ): Mono<ResponseEntity<ErrorDescription>> {
        log.debug("received bad request:", e)
        return errorDescription(
            exchange,
            httpStatus = BAD_REQUEST,
            message = "The request's body could not be read. It is either empty or malformed."
        )
    }

    /** In case the request body is malformed or non existing. */
    @ResponseStatus(BAD_REQUEST)
    @ExceptionHandler(ServerWebInputException::class)
    fun handle(
        e: ServerWebInputException,
        exchange: ServerWebExchange
    ): Mono<ResponseEntity<ErrorDescription>> {
        log.debug("received bad request:", e)
        // this is an ugly workaround - with mvc the type mismatch error would not
        // be hidden away as the cause, but on webflux we need to dig like this
        return when (val cause = e.cause) {
            is TypeMismatchException -> errorDescription(
                exchange,
                httpStatus = BAD_REQUEST,
                message = "The parameter '${cause.value}' is malformed."
            )
            else -> errorDescription(
                exchange,
                httpStatus = BAD_REQUEST,
                message = "The request's body could not be read. It is either empty or malformed."
            )
        }
    }

    /** In case a validation on a request body property fails */
    @ResponseStatus(BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handle(
        e: MethodArgumentNotValidException,
        exchange: ServerWebExchange
    ): Mono<ResponseEntity<ErrorDescription>> {
        log.debug("received bad request:", e)

        val fieldDetails = e.bindingResult.fieldErrors.map { "The field '${it.field}' ${it.defaultMessage}." }
        val globalDetails = e.bindingResult.globalErrors.map { it.defaultMessage }
        val details = fieldDetails + globalDetails
        val sortedDetails = details.sorted()

        return errorDescription(
            exchange,
            httpStatus = BAD_REQUEST,
            message = "The request's body is invalid. See details...",
            details = sortedDetails
        )
    }

    /** In case a validation on a request body property fails */
    @ResponseStatus(BAD_REQUEST)
    @ExceptionHandler(WebExchangeBindException::class)
    fun handle(
        e: WebExchangeBindException,
        exchange: ServerWebExchange
    ): Mono<ResponseEntity<ErrorDescription>> {
        log.debug("received bad request:", e)

        val fieldDetails = e.bindingResult.fieldErrors.map { "The field '${it.field}' ${it.defaultMessage}." }
        val globalDetails = e.bindingResult.globalErrors.map { it.defaultMessage }
        val details = fieldDetails + globalDetails
        val sortedDetails = details.sorted()

        return errorDescription(
            exchange,
            httpStatus = BAD_REQUEST,
            message = "The request's body is invalid. See details...",
            details = sortedDetails
        )
    }

    @ResponseStatus(FORBIDDEN)
    @ExceptionHandler(AccessDeniedException::class)
    fun handle(
        e: AccessDeniedException,
        exchange: ServerWebExchange
    ): Mono<ResponseEntity<ErrorDescription>> {
        val userName = SecurityContextHolder.getContext()?.authentication?.name
        val request = exchange.request
        log.debug("blocked illegal access from user [{}]: {} {}", userName, request.method, request.uri)
        return errorDescription(
            exchange,
            httpStatus = FORBIDDEN,
            message = "You don't have the necessary rights to to this."
        )
    }

    /** In case any other exception occurs. */
    @ResponseStatus(INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception::class)
    fun handle(
        e: Exception,
        exchange: ServerWebExchange
    ): Mono<ResponseEntity<ErrorDescription>> {
        log.error("internal server error occurred:", e)
        return errorDescription(
            exchange,
            httpStatus = INTERNAL_SERVER_ERROR,
            message = "An internal server error occurred, see server logs for more information."
        )
    }

    private fun errorDescription(
        exchange: ServerWebExchange,
        httpStatus: HttpStatus,
        message: String,
        details: List<String> = emptyList()
    ): Mono<ResponseEntity<ErrorDescription>> {
        val traceId = exchange.request.headers["X-B3-TraceId"]?.firstOrNull()
        val description = ErrorDescription(
            status = httpStatus.value(),
            error = httpStatus.reasonPhrase,
            timestamp = OffsetDateTime.now(clock).toString(),
            correlationId = traceId,
            message = message,
            details = details
        )
        return Mono.just(
            ResponseEntity(
                description,
                HttpHeaders().apply { contentType = APPLICATION_JSON },
                httpStatus
            )
        )
    }

}