package library.service.logging

import brave.Tracer
import brave.Tracing
import io.mockk.every
import io.mockk.mockk
import library.service.security.SecurityConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient
import org.testit.testutils.logrecorder.api.LogRecord
import org.testit.testutils.logrecorder.junit5.RecordLoggers
import reactor.core.publisher.Mono
import utils.MutableClock
import utils.ResetMocksAfterEachTest
import utils.classification.IntegrationTest
import utils.testapi.TestController
import utils.testapi.TestService

@IntegrationTest
@ResetMocksAfterEachTest
@WebFluxTest(TestController::class)
internal class RequestLoggingFilterIntTest(
    @Autowired val clock: MutableClock,
    @Autowired val testClient: WebTestClient,
    @Autowired val testService: TestService
) {

    @TestConfiguration
    @ComponentScan("utils.testapi")
    @Import(SecurityConfiguration::class)
    class AdditionalBeans {
        @Bean
        fun tracer(): Tracer = Tracing.newBuilder().build().tracer()

        @Bean
        fun testService(): TestService = mockk()
    }

    @BeforeEach
    fun setTime() {
        clock.setFixedTime("2017-09-01T12:34:56.789Z")
        every { testService.doSomething() } returns Mono.empty()
    }

    @RecordLoggers(RequestLoggingFilter::class)
    @Test
    fun `processing a request generates 2 log entries`(log: LogRecord) = aRequestWillProduceLog(log) { messages ->
        assertThat(messages).hasSize(2)
    }

    @RecordLoggers(RequestLoggingFilter::class)
    @Test
    fun `log entries are formatted correctly`(log: LogRecord) = aRequestWillProduceLog(log) { messages ->
        assertThat(messages[0]).matches("""Received Request \[(.+?)]""")
        assertThat(messages[1]).matches("""Processed Request \[(.+?)]""")
    }

    @RecordLoggers(RequestLoggingFilter::class)
    @Test
    fun `uri is logged with query strings`(log: LogRecord) = aRequestWillProduceLog(log) { messages ->
        assertThat(messages[0]).contains("/test?foo=bar,")
    }

    @RecordLoggers(RequestLoggingFilter::class)
    @Test
    fun `request headers are logged`(log: LogRecord) = aRequestWillProduceLog(log) { messages ->
        assertThat(messages[0]).contains("headers=[WebTestClient-Request-Id:")
    }

    private fun aRequestWillProduceLog(log: LogRecord, body: (List<String>) -> Unit) {
        testClient.post().uri("/test?foo=bar").exchange()
        body(log.messages)
    }

}