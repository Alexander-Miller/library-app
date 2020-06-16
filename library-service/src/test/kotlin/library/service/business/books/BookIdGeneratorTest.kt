package library.service.business.books

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import utils.classification.UnitTest

@UnitTest
internal class BookIdGeneratorTest {

    val dataStore: BookDataStore = mockk()
    val cut = BookIdGenerator(dataStore)

    @Test
    fun `book IDs can be generated`() {
        every { dataStore.existsById(any()) } returns Mono.just(false)
        val bookId = cut.generate().block()
        assertThat(bookId).isNotNull
    }

    @Test
    fun `id generation is retried in case the generated id already exists`() {
        every { dataStore.existsById(any()) }
            .returns(Mono.just(true))
            .andThen(Mono.just(true))
            .andThen(Mono.just(false))

        cut.generate().block()

        verify(exactly = 3) { dataStore.existsById(any()) }
    }

}
