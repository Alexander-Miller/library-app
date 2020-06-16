package utils

import library.service.security.Authorizations
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Returns a [Clock] with a fixed value of the given ISO timestamp.
 *
 * Example: `2017-10-30T12:34:56.789Z`.
 */
fun clockWithFixedTime(isoTimestamp: String): Clock {
    val timestamp = ZonedDateTime.parse(isoTimestamp)
    return Clock.fixed(timestamp.toInstant(), ZoneId.from(timestamp))
}

/**
 * Executes the given code block with a custom security context.
 * Any originally set context is remembered and restored after the execution.
 *
 * This can be used to execute code which requires a security context in tests.
 * E.g. when calling business functions directly without providing credentials
 * via the API.
 */
fun <T : Any> executeAsUserWithRole(
    username: String = "testuser",
    password: String = "password",
    role: String,
    body: () -> Mono<T>
): T {
    val authentication = UsernamePasswordAuthenticationToken(username, password, listOf(SimpleGrantedAuthority(role)))
    val context = ReactiveSecurityContextHolder.withAuthentication(authentication)
    return body().subscriberContext(context).block()!!
}
