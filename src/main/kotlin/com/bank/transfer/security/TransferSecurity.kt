package com.bank.transfer.security

import com.bank.transfer.config.TransferSecurityProperties
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.stereotype.Component
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono
import java.util.UUID

private val HEALTH_ENDPOINTS = arrayOf(
    "/actuator/health",
    "/actuator/health/**",
    "/actuator/info",
)

@Configuration(proxyBeanMethods = false)
@EnableReactiveMethodSecurity
@ConditionalOnProperty(prefix = "transfer.security", name = ["mode"], havingValue = "mock-header")
class MockTransferSecurityConfiguration {
    @Bean
    @Order(0)
    fun mockSecurityChain(
        http: ServerHttpSecurity,
        customerHeaderFilter: MockCustomerHeaderFilter,
    ): SecurityWebFilterChain = http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .authorizeExchange { exchanges ->
            exchanges.pathMatchers(*HEALTH_ENDPOINTS).permitAll()
            exchanges.anyExchange().authenticated()
        }
        .addFilterAt(
            customerHeaderFilter,
            org.springframework.security.config.web.server.SecurityWebFiltersOrder.AUTHENTICATION,
        )
        .build()

    @Bean
    fun customerHeaderFilter(
        properties: TransferSecurityProperties,
    ): MockCustomerHeaderFilter = MockCustomerHeaderFilter(properties.customerHeader)
}

@Configuration(proxyBeanMethods = false)
@EnableReactiveMethodSecurity
@ConditionalOnProperty(
    prefix = "transfer.security",
    name = ["mode"],
    havingValue = "jwt",
    matchIfMissing = true,
)
class JwtTransferSecurityConfiguration {
    @Bean
    @Order(0)
    fun jwtSecurityChain(http: ServerHttpSecurity): SecurityWebFilterChain = http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .authorizeExchange { exchanges ->
            exchanges.pathMatchers(*HEALTH_ENDPOINTS).permitAll()
            exchanges.pathMatchers("/api/**").hasAuthority("SCOPE_transfers")
            exchanges.anyExchange().denyAll()
        }
        .oauth2ResourceServer { resourceServer -> resourceServer.jwt { } }
        .build()
}

/** Local-only authentication bridge used by README commands and integration tests. */
class MockCustomerHeaderFilter(
    private val customerHeader: String,
) : WebFilter {
    override fun filter(
        exchange: org.springframework.web.server.ServerWebExchange,
        chain: org.springframework.web.server.WebFilterChain,
    ): Mono<Void> {
        val rawCustomer = exchange.request.headers.getFirst(customerHeader)
            ?: return chain.filter(exchange)
        val customerId = runCatching { UUID.fromString(rawCustomer) }
            .getOrElse {
                exchange.response.statusCode = org.springframework.http.HttpStatus.BAD_REQUEST
                return exchange.response.setComplete()
            }
        val authentication = UsernamePasswordAuthenticationToken.authenticated(
            customerId.toString(),
            "local-profile",
            listOf(SimpleGrantedAuthority("SCOPE_transfers")),
        )
        return chain.filter(exchange)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
    }
}

@Component
class CurrentCustomerProvider {
    suspend fun currentCustomerId(): UUID {
        val authentication = ReactiveSecurityContextHolder.getContext()
            .map { context -> context.authentication }
            .filter(Authentication::isAuthenticated)
            .awaitSingleOrNull()
            ?: throw org.springframework.security.access.AccessDeniedException("Authentication is required")
        return runCatching { UUID.fromString(authentication.name) }
            .getOrElse {
                throw org.springframework.security.access.AccessDeniedException(
                    "Authenticated principal is not a customer identifier",
                )
            }
    }
}
