package library.service.security

import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.core.context.SecurityContext
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class UserContext {

    fun isCurator() = currentUserHasRole(Authorizations.CURATOR_ROLE)

    private fun currentUserHasRole(role: String): Mono<Boolean> =
        ReactiveSecurityContextHolder.getContext().map { ctx: SecurityContext? ->
            ctx?.authentication
                ?.authorities
                ?.any { it.authority == role }
                ?: true
        }.switchIfEmpty(Mono.just(true))
}