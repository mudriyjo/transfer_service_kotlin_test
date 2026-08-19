package com.bank.transfer.integration.cbs
import com.bank.transfer.config.CbsHttpProperties
import io.netty.channel.ChannelOption
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.util.retry.Retry

/** HTTP client used when CBS integration mode is enabled. */
@Component
@ConditionalOnProperty(
    prefix = "transfer.cbs",
    name = ["mode"],
    havingValue = "http",
    matchIfMissing = true,
)
class HttpCbsTransferClient @Autowired constructor(
    builder: WebClient.Builder,
    properties: CbsHttpProperties,
) : CbsTransferClient {
    private val requestTimeout = properties.readTimeout
    private val totalAttempts = properties.retry.maxAttempts
    private val webClient = builder
        .clone()
        .baseUrl(properties.baseUrl)
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        properties.connectTimeout.toMillis().coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                    )
                    .responseTimeout(requestTimeout),
            ),
        )
        .build()

    internal constructor(
        builder: WebClient.Builder,
        baseUrl: String,
        connectTimeout: Duration = Duration.ofSeconds(2),
        requestTimeout: Duration,
        totalAttempts: Long,
    ) : this(
        builder = builder,
        properties = CbsHttpProperties.of(baseUrl, connectTimeout, requestTimeout, totalAttempts),
    )

    override suspend fun transfer(request: CbsTransferRequest): CbsTransferResponse =
        webClient
            .post()
            .uri("/api/v1/transfers")
            .bodyValue(request)
            .exchangeToMono<CbsTransferResponse> { response ->
                when {
                    response.statusCode().is2xxSuccessful ->
                        response.bodyToMono(CbsTransferResponse::class.java)
                    response.statusCode().value() == 409 ->
                        Mono.error(CbsReferenceConflictException(request.clientReference))
                    else -> response.toRejectedException()
                }
            }
            .timeout(requestTimeout)
            .retryWhen(broadRetry())
            .onErrorMap(TimeoutException::class.java) { error ->
                CbsTimeoutException(request.clientReference, mayHaveCommitted = true, cause = error)
            }
            .awaitSingle()

    override suspend fun status(clientReference: String): CbsStatusResponse =
        webClient
            .get()
            .uri("/api/v1/transfers/{clientReference}", clientReference)
            .exchangeToMono<CbsStatusResponse> { response ->
                when {
                    response.statusCode().is2xxSuccessful ->
                        response.bodyToMono(CbsStatusResponse::class.java)
                    response.statusCode().value() == 404 ->
                        Mono.just(
                            CbsStatusResponse(
                                operationId = null,
                                clientReference = clientReference,
                                status = CbsOperationStatus.NOT_FOUND,
                                processedAt = null,
                            ),
                        )
                    else -> response.toRejectedException()
                }
            }
            .timeout(requestTimeout)
            .retryWhen(broadRetry())
            .onErrorMap(TimeoutException::class.java) { error ->
                CbsTimeoutException(clientReference, mayHaveCommitted = false, cause = error)
            }
            .awaitSingle()

    private fun broadRetry(): Retry = Retry.max((totalAttempts.coerceAtLeast(1) - 1))

    private fun <T> org.springframework.web.reactive.function.client.ClientResponse.toRejectedException(): Mono<T> =
        bodyToMono(String::class.java)
            .defaultIfEmpty("CBS returned HTTP ${statusCode().value()}")
            .flatMap { body ->
                Mono.error<T>(
                    CbsRejectedException(
                        code = statusCode().toFailureCode(),
                        message = body,
                    ),
                )
            }

    private fun HttpStatusCode.toFailureCode(): String = "CBS_HTTP_${value()}"
}
