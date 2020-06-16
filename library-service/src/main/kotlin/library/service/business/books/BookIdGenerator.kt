package library.service.business.books

import library.service.business.books.domain.types.BookId
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class BookIdGenerator(
        private val dataStore: BookDataStore
) {

    fun generate(): Mono<BookId> {
        val bookId = BookId.generate()
        return dataStore.existsById(bookId).flatMap { exists ->
            if (exists) {
                generate()
            } else {
                Mono.just(bookId)
            }
        }
    }

}