package library.service.api.errors

import com.fasterxml.jackson.databind.ObjectMapper
import library.service.logging.logger
import org.springframework.boot.autoconfigure.web.ResourceProperties
import org.springframework.boot.autoconfigure.web.reactive.error.AbstractErrorWebExceptionHandler
import org.springframework.boot.web.error.ErrorAttributeOptions
import org.springframework.boot.web.error.ErrorAttributeOptions.Include
import org.springframework.boot.web.error.ErrorAttributeOptions.Include.MESSAGE
import org.springframework.boot.web.error.ErrorAttributeOptions.Include.STACK_TRACE
import org.springframework.boot.web.reactive.error.ErrorAttributes
import org.springframework.context.ApplicationContext
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.*
import java.time.Clock
import java.time.OffsetDateTime

private typealias RequestErrorAttributes = Map<String, Any>

@Component
@Order(-2)
@SuppressWarnings("MagicNumber")
class GlobalErrorWebExceptionHandler(
    private val serverCodecConfigurer: ServerCodecConfigurer,
    private val clock: Clock,
    private val errorAttributes: ErrorAttributes,
    private val objectMapper: ObjectMapper,
    resourceProperties: ResourceProperties,
    applicationContext: ApplicationContext
) : AbstractErrorWebExceptionHandler(errorAttributes, resourceProperties, applicationContext) {

    private val log = GlobalErrorWebExceptionHandler::class.logger

    private val requestPredicate: RequestPredicate = RequestPredicate { true }

    private val handlerFunction = HandlerFunction { request ->
        val errorResponseWrapper = createResponse(request)
        with(errorResponseWrapper) {
            when (response.status) {
                in 400..499 -> log.warn("Client error occurred: $completeErrorData")
                in 500..599 -> log.error("Server error occurred: $completeErrorData")
                else -> log.debug("Unknown error occurred: $completeErrorData")
            }
        }
        ServerResponse.status(errorResponseWrapper.response.status)
            .contentType(APPLICATION_JSON)
            .bodyValue(errorResponseWrapper.response)
    }

    override fun afterPropertiesSet() {
        setMessageWriters(serverCodecConfigurer.writers)
        super.afterPropertiesSet()
    }

    override fun getRoutingFunction(errorAttributes: ErrorAttributes): RouterFunction<ServerResponse> =
        RouterFunctions.route(requestPredicate, handlerFunction)


    fun createResponse(request: ServerRequest): ErrorResponseWrapper {
        val errorAttributes = getErrorAttributes(request)
        val currentPath = request.path()
        val traceId = request.traceId ?: "000000"
        val response = ErrorResponse(
            timestamp = clock.now,
            path = errorAttributes.path ?: currentPath,
            status = errorAttributes.status,
            error = errorAttributes.error,
            message = errorAttributes.errorMessage,
            details = errorAttributes.errorDetails,
            correlationId = traceId
        )
        return ErrorResponseWrapper(
            response = response,
            completeErrorData = objectMapper.writeValueAsString(response)
        )
    }

    private fun getErrorAttributes(request: ServerRequest): RequestErrorAttributes {
        return errorAttributes.getErrorAttributes(
            request,
            ErrorAttributeOptions.of(Include.BINDING_ERRORS, Include.EXCEPTION, MESSAGE, STACK_TRACE
            )
        )
    }

    private val Clock.now: String
        get() = OffsetDateTime.now(this).toString()
    private val RequestErrorAttributes.path: String?
        get() = this["path"]?.toString()
    private val RequestErrorAttributes.status: Int
        get() = (this["status"] as Int?) ?: 0
    private val RequestErrorAttributes.error: String
        get() = this["error"]?.toString() ?: ""
    private val RequestErrorAttributes.errorMessage: String?
        get() = this["message"]?.toString()
    private val RequestErrorAttributes.errorDetails: List<String>?
        get() = (this["errors"] as List<*>?)?.map { it.toString() }
    private val ServerRequest.traceId: String?
        get() = this.headers().header("X-B3-TraceId").firstOrNull()
}