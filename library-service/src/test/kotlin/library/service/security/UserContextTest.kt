package library.service.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder.*
import org.springframework.security.core.context.SecurityContextImpl
import reactor.core.publisher.Mono
import reactor.util.context.Context
import utils.classification.UnitTest

@UnitTest
internal class UserContextTest {

    val userRole = SimpleGrantedAuthority(Authorizations.USER_ROLE)
    val curatorRole = SimpleGrantedAuthority(Authorizations.CURATOR_ROLE)

    val cut = UserContext()

    @Nested
    inner class `user is considered a curator` {

        @Test
        fun `if there is no security context`() {
            assertThat(cut.isCurator().block()!!).isTrue()

        }

        @Test
        fun `if the security context is empty`() {
            assertThat(cut.isCurator().block()!!).isTrue()
        }

        @Test
        fun `if the user has role CURATOR`() {
            val securityContext = securityContext(withAuthorizations(userRole, curatorRole))
            assertThat(cut.isCurator().subscriberContext(securityContext).block()!!).isTrue()
        }

    }

    @Test
    fun `user is not considered a curator if CURATOR role is missing`() {
        val securityContext = securityContext(withAuthorizations(userRole))
        assertThat(cut.isCurator().subscriberContext(securityContext).block()!!).isFalse()
    }

    fun securityContext(authentication: Authentication): Context =
        withAuthentication(authentication)

    fun withAuthorizations(vararg authorities: GrantedAuthority) =
        UsernamePasswordAuthenticationToken("username", "password", listOf(*authorities))

}