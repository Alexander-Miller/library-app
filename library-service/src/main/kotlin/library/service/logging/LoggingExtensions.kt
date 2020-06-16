package library.service.logging

import brave.Tracer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/** Returns the [Logger] instance of this [KClass]. */
val KClass<*>.logger: Logger get() = LoggerFactory.getLogger(java)

fun Tracer.traceId(): String? = this.currentSpan()?.context()?.traceIdString()

