package library.service.database

import org.springframework.data.mongodb.repository.ReactiveMongoRepository
import reactor.core.publisher.Mono
import java.util.*

interface BookRepository : ReactiveMongoRepository<BookDocument, UUID> {
    fun countByBorrowedNotNull(): Mono<Long>
}