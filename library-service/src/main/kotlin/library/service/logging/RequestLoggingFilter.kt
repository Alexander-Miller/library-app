package library.service.logging

import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * [WebFilter] responsible for logging all request to this service's HTTP
 * endpoints.
 *
 * This filter is only active if the log level for this class is set to `DEBUG`
 * or lower. Log entries are created on the `DEBUG` level.
 *
 * The logged information includes:
 * - request and response headers
 * - query strings
 *
 */
@Component
class RequestLoggingFilter : WebFilter {

    companion object {
        private const val BEFORE_MESSAGE_PREFIX: String = "Received Request ["
        private const val BEFORE_MESSAGE_SUFFIX: String = "]"
        private const val AFTER_MESSAGE_PREFIX: String = "Processed Request ["
        private const val AFTER_MESSAGE_SUFFIX: String = "]"
        private val ALREADY_FILTERED_ATTRIBUTE: String = RequestLoggingFilter::javaClass.name + ".FILTERED"
    }

    private val log = RequestLoggingFilter::class.logger

    fun shouldLog() = log.isDebugEnabled

    override fun filter(exchange: ServerWebExchange, filterChain: WebFilterChain): Mono<Void> {
        val hasAlreadyFilteredAttribute = exchange.attributes[ALREADY_FILTERED_ATTRIBUTE] != null

        if (!shouldLog()) {
            return filterChain.filter(exchange)
        } else if (hasAlreadyFilteredAttribute) {
            return filterChain.filter(exchange)
        } else {
            exchange.attributes[ALREADY_FILTERED_ATTRIBUTE] = true
            try {
                if (shouldLog()) {
                    log.debug(createMessage(exchange, BEFORE_MESSAGE_PREFIX, BEFORE_MESSAGE_SUFFIX))
                }
                try {
                    return filterChain.filter(exchange)
                } finally {
                    if (shouldLog()) {
                        log.debug(createMessage(exchange, AFTER_MESSAGE_PREFIX, AFTER_MESSAGE_SUFFIX))
                    }
                }
            } finally {
                exchange.attributes.remove(ALREADY_FILTERED_ATTRIBUTE)
            }
        }
    }

    private fun createMessage(
        exchange: ServerWebExchange,
        prefix: String,
        suffix: String
    ): String {
        val request = exchange.request
        val msg = StringBuilder()
        msg.append(prefix)
        msg.append(request.method).append(" ")
        msg.append(request.uri.path)
        val queryParams = request.queryParams
        if (queryParams.isNotEmpty()) {
            val paramsAsString = queryParams.map { (k, v) ->
                when (v.size > 1) {
                    true -> "$k=[${v.joinToString { "," }}]"
                    false -> "$k=${v.first()}"
                }
            }.joinToString(",")
            msg.append('?').append(paramsAsString)
        }
        val client = request.remoteAddress?.toString() ?: "null"
        msg.append(", client=").append(client)
        msg.append(", headers=").append(request.headers)
        msg.append(suffix)
        return msg.toString()
    }
}
