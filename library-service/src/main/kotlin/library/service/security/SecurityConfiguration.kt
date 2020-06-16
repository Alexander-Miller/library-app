package library.service.security

import library.service.security.Roles.ACTUATOR
import library.service.security.Roles.CURATOR
import library.service.security.Roles.USER
import library.service.security.UserSettings.UserCredentials
import org.springframework.boot.actuate.autoconfigure.security.reactive.EndpointRequest.to
import org.springframework.boot.actuate.autoconfigure.security.reactive.EndpointRequest.toAnyEndpoint
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.info.InfoEndpoint
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod.GET
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ReactiveAuthenticationManager
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebFluxSecurity
class SecurityConfiguration {

    @Configuration
    @ConditionalOnProperty("application.secured", havingValue = "false", matchIfMissing = false)
    class UnsecuredConfiguration {
        @Bean
        fun unsecuredWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
            return http.csrf().disable()
                .authorizeExchange().anyExchange().permitAll()
                .and()
                .build()
        }
    }

    @Configuration
    @ConditionalOnProperty("application.secured", havingValue = "true", matchIfMissing = true)
    @EnableReactiveMethodSecurity
    @EnableConfigurationProperties(UserSettings::class, CorsSettings::class)
    class SecuredConfiguration(
        private val userSettings: UserSettings,
        private val corsSettings: CorsSettings
    ) {

        private val infoEndpoint = InfoEndpoint::class.java
        private val healthEndpoint = HealthEndpoint::class.java
        private val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

        @Bean
        fun securedWebFilterChain(
            http: ServerHttpSecurity,
            userSettings: UserSettings,
            corsSettings: CorsSettings,
            authManager: ReactiveAuthenticationManager
        ): SecurityWebFilterChain {
            return http.csrf().disable()
                .cors()
                .and()
                .httpBasic()
                .authenticationManager(authManager)
                .and()
                .requestCache().requestCache(NoOpServerRequestCache.getInstance())
                .and()
                .formLogin().disable()
                .authorizeExchange { spec ->
                    spec.pathMatchers(GET, "/", "/help", "/docs", "/docs/**").permitAll()
                    spec.matchers(to(infoEndpoint, healthEndpoint)).permitAll()
                    spec.matchers(toAnyEndpoint()).hasRole(ACTUATOR)
                    spec.anyExchange().authenticated()
                }
                .build()
        }

        @Bean
        fun userDetailsService(): MapReactiveUserDetailsService {
            return MapReactiveUserDetailsService(
                userSettings.admin.toUser(USER, CURATOR, ACTUATOR),
                userSettings.curator.toUser(USER, CURATOR),
                userSettings.user.toUser(USER)
            )
        }

        @Bean
        fun authManager(userService: ReactiveUserDetailsService): ReactiveAuthenticationManager {
            return UserDetailsRepositoryReactiveAuthenticationManager(userService)
        }


        private fun UserCredentials.toUser(
            vararg roles: String
        ) = User.withUsername(username)
            .password(encoder.encode(password))
            .roles(*roles)
            .build()

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource {
            val configuration = CorsConfiguration().apply {
                allowedOrigins = corsSettings.origins
                allowedMethods = corsSettings.methods
                allowedHeaders = mutableListOf("*")
                allowCredentials = true
            }
            return UrlBasedCorsConfigurationSource().apply {
                registerCorsConfiguration("/**", configuration)
            }
        }

    }
}
