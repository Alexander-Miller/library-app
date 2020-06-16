package library.service

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.hateoas.config.EnableHypermediaSupport
import org.springframework.hateoas.config.EnableHypermediaSupport.HypermediaType.HAL
import org.springframework.hateoas.support.WebStack.WEBFLUX
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.reactive.result.view.RedirectView
import org.springframework.web.server.adapter.ForwardedHeaderTransformer
import java.time.Clock


@EnableWebFlux
@SpringBootApplication(exclude = [SecurityAutoConfiguration::class])
@EnableHypermediaSupport(type = [HAL], stacks = [WEBFLUX])
class Application {

    @ConditionalOnMissingBean
    @Bean
    fun utcClock(): Clock = Clock.systemUTC()

    @Bean
    fun forwardedHeaderTransformer(): ForwardedHeaderTransformer {
        return ForwardedHeaderTransformer()
    }

    @Controller
    class RedirectController {

        @GetMapping("/")
        fun redirectIndexToDocumentation() = RedirectView("/docs/index.html")

        @GetMapping("/docs")
        fun redirectDocsToDocumentation() = RedirectView("/docs/index.html")

        @GetMapping("/help")
        fun redirectHelpToDocumentation() = RedirectView("/docs/index.html")

    }

}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
