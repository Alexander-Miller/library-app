package library.service.api

import brave.Tracer
import brave.Tracing
import io.mockk.every
import io.mockk.mockk
import library.service.api.errors.GlobalErrorWebExceptionHandler
import library.service.business.exceptions.MalformedValueException
import library.service.business.exceptions.NotFoundException
import library.service.business.exceptions.NotPossibleException
import library.service.security.SecurityConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatus.*
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.validation.BindingResult
import org.springframework.validation.FieldError
import org.springframework.validation.ObjectError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import reactor.core.publisher.Mono
import utils.MutableClock
import utils.classification.IntegrationTest
import utils.testapi.TestController
import utils.testapi.TestService
import java.lang.RuntimeException
import java.util.*

@IntegrationTest
@WebFluxTest(value = [TestController::class, GlobalErrorWebExceptionHandler::class])
@ComponentScan("utils.testapi")
internal class ErrorHandlersIntTest(
    @Autowired val testClient: WebTestClient,
    @Autowired val clock: MutableClock,
    @Autowired val testService: TestService
) {

    val traceId = UUID.randomUUID().toString()

    @TestConfiguration
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
    }

    @Test
    fun `NotFoundException is handled`() {
        executionWillThrow { NotFoundException("something was not found") }
        executeAndExpect(NOT_FOUND) {
            """
            {
              "path": "/test",
              "status": 404,
              "error": "Not Found",
              "timestamp": "2017-09-01T12:34:56.789Z",
              "correlationId": "$traceId",
              "message": "something was not found"
            }
            """
        }
    }

    @Test
    fun `NotPossibleException is handled`() {
        executionWillThrow { NotPossibleException("something could not be done") }
        executeAndExpect(CONFLICT) {
            """
            {
              "path": "/test",
              "status": 409,
              "error": "Conflict",
              "timestamp": "2017-09-01T12:34:56.789Z",
              "correlationId": "$traceId",
              "message": "something could not be done"
            }
            """
        }
    }

    @Test
    fun `MalformedValueException is handled`() {
        executionWillThrow { MalformedValueException("some value was wrong") }
        executeAndExpect(BAD_REQUEST) {
            """
            {
              "path": "/test",
              "status": 400,
              "error": "Bad Request",
              "timestamp": "2017-09-01T12:34:56.789Z",
              "correlationId": "$traceId",
              "message": "some value was wrong"
            }
            """
        }
    }

    @Test
    fun `MethodArgumentTypeMismatchException is handled`() {
        executionWillThrow {
            MethodArgumentTypeMismatchException(
                "value",
                String::class.java,
                "myArgument",
                mockk(),
                RuntimeException("ERR")
            )
        }
        executeAndExpect(BAD_REQUEST) {
            """
            {
              "path": "/test",
              "status": 400,
              "error": "Bad Request",
              "timestamp": "2017-09-01T12:34:56.789Z",
              "correlationId": "$traceId",
              "message": "The request's 'myArgument' parameter is malformed."
            }
            """
        }
    }

    @Test
    fun `HttpMessageNotReadableException is handled`() {
        executionWillThrow { HttpMessageNotReadableException("this will not be exposed", mockk<HttpInputMessage>()) }
        executeAndExpect(BAD_REQUEST) {
            """
            {
              "path": "/test",
              "status": 400,
              "error": "Bad Request",
              "timestamp": "2017-09-01T12:34:56.789Z",
              "correlationId": "$traceId",
              "message": "The request's body could not be read. It is either empty or malformed."
            }
            """
        }
    }

    @Test
    fun `MethodArgumentNotValidException is handled`() {
        val bindingResult = bindingResult()
        executionWillThrow { MethodArgumentNotValidException(mockk(relaxed = true), bindingResult) }
        executeAndExpect(BAD_REQUEST) {
            """
            {
              "path": "/test",
              "status": 400,
              "error": "Bad Request",
              "timestamp": "2017-09-01T12:34:56.789Z",
              "correlationId": "$traceId",
              "message": "The request's body is invalid. See details...",
              "details": [
                "Gloabl Message 1",
                "Gloabl Message 2",
                "The field 'field1' Message about field1.",
                "The field 'field2' Message about field2."
              ]
            }
            """
        }
    }

    private fun bindingResult(): BindingResult {
        val fieldError1 = FieldError("objectName", "field1", "Message about field1")
        val fieldError2 = FieldError("objectName", "field2", "Message about field2")
        val globalError1 = ObjectError("objectName", "Gloabl Message 1")
        val globalError2 = ObjectError("objectName", "Gloabl Message 2")

        return mockk {
            every { fieldErrors } returns listOf(fieldError1, fieldError2)
            every { globalErrors } returns listOf(globalError1, globalError2)
            every { errorCount } returns 2
            every { allErrors } returns listOf(fieldError1, fieldError2, globalError1, globalError2)
        }
    }

    @Test
    fun `AccessDeniedException is handled`() {
        executionWillThrow { AccessDeniedException("missing right") }
        executeAndExpect(FORBIDDEN) {
            """
            {
              "path": "/test",
              "status": 403,
              "error": "Forbidden",
              "timestamp": "2017-09-01T12:34:56.789Z",
              "correlationId": "$traceId",
              "message": "You don't have the necessary rights to to this."
            }
            """
        }
    }

    @Test
    fun `Exception is handled`() {
        executionWillThrow { Exception("this will not be exposed") }
        executeAndExpect(INTERNAL_SERVER_ERROR) {
            """
            {
              "path": "/test",
              "status": 500,
              "error": "Internal Server Error",
              "timestamp": "2017-09-01T12:34:56.789Z",
              "correlationId": "$traceId",
              "message": "An internal server error occurred, see server logs for more information."
            }
            """
        }
    }

    private fun executionWillThrow(exceptionSupplier: () -> Throwable) {
        every { testService.doSomething() } returns Mono.error(exceptionSupplier())
    }

    private fun executeAndExpect(expectedStatus: HttpStatus, expectedResponseSupplier: () -> String) {
        testClient.post()
            .uri("/test")
            .header("X-B3-TraceId", traceId)
            .exchange()
            .expectStatus().isEqualTo(expectedStatus)
            .expectHeader().contentType(APPLICATION_JSON)
            .expectBody()
            .json(expectedResponseSupplier())
    }

}