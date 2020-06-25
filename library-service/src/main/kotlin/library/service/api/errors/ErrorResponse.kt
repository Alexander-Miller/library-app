package library.service.api.errors

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime

/**
 * Response object used to describe errors to API consumers.
 *
 * You should _never_ create an instance of this class directly. Always use the
 * [ErrorResponseFactory] in order to correctly handle sensitive information!
 *
 * @see ErrorResponseFactory
 */
@JsonInclude(NON_NULL)
data class ErrorResponse(
    /** The exact point in time this error occurred. Should be serialized as an ISO 8601 timestamp. */
    val timestamp: String,
    /** The request full path on which the error occurred incl. optional context path prefix. */
    var path: String,
    /** The response's HTTP status (e.g. 400, 404, 500 etc.). */
    var status: Int,
    /** The short error description corresponding with the `httpStatus` property (e.g. 'Bad Request').*/
    var error: String,
    /** The error message describing the underlying technical error. Exposing this can be configured. */
    var message: String?,
    /** Zero or more details further describing the technical error. Exposing this can be configured. */
    var details: List<String>?,
    /** The request's trace ID uniquely identifying a single logical operation spanning multiple services. */
    var correlationId: String?
)

data class ErrorResponseWrapper(
    val response: ErrorResponse,
    val completeErrorData: String
)
