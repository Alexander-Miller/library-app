package library.service.api.index

import library.service.api.books.BooksController
import library.service.security.UserContext
import org.springframework.hateoas.Link
import org.springframework.hateoas.RepresentationModel
import org.springframework.hateoas.server.reactive.WebFluxLinkBuilder.linkTo
import org.springframework.hateoas.server.reactive.WebFluxLinkBuilder.methodOn
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api")
class IndexController(
    private val currentUser: UserContext
) {

    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    fun get(): Mono<VoidRepresentationModel> {
        val selfLink = linkTo(methodOn(IndexController::class.java).get()).withSelfRel().toMono()
        val getBooksLink = linkTo(methodOn(BooksController::class.java).getBooks()).withRel("getBooks").toMono()
        val addBookLink = currentUser.isCurator().flatMap {
            when (it) {
                true -> linkTo(methodOn(BooksController::class.java)).withRel("addBook").toMono()
                false -> Mono.empty()
            }
        }
        val allLinks = Flux.concat(selfLink, getBooksLink, addBookLink)
        return allLinks.collectList().map { links ->
            VoidRepresentationModel(links)
        }
    }

    open class VoidRepresentationModel(links: Iterable<Link>) : RepresentationModel<VoidRepresentationModel>(links)

}