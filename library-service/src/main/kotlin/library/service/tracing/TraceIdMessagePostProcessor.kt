package library.service.tracing

import brave.Tracer
import library.service.logging.logger
import library.service.logging.traceId
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessagePostProcessor
import org.springframework.stereotype.Component

@Component
class TraceIdMessagePostProcessor(
    private val tracer: Tracer
) : MessagePostProcessor {

    private val log = TraceIdMessagePostProcessor::class.logger

    override fun postProcessMessage(message: Message) = message.apply {
        val traceId = tracer.traceId()
        log.debug("setting message trace ID to [{}]", traceId)
        messageProperties.correlationId = traceId
    }

}