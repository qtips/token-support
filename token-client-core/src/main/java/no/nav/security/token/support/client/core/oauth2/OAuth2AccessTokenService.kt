package no.nav.security.token.support.client.core.oauth2

import com.github.benmanes.caffeine.cache.Cache
import java.util.Arrays
import java.util.Optional
import java.util.function.Function
import org.slf4j.LoggerFactory
import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.OAuth2ClientException
import no.nav.security.token.support.client.core.OAuth2GrantType
import no.nav.security.token.support.client.core.context.JwtBearerTokenResolver

class OAuth2AccessTokenService @JvmOverloads constructor(private val tokenResolver : JwtBearerTokenResolver,
                               private val onBehalfOfTokenClient : OnBehalfOfTokenClient,
                               private val clientCredentialsTokenClient : ClientCredentialsTokenClient,
                               private val tokenExchangeClient : TokenExchangeClient,
                               var clientCredentialsGrantCache : Cache<ClientCredentialsGrantRequest, OAuth2AccessTokenResponse>? = null,
                               var exchangeGrantCache : Cache<TokenExchangeGrantRequest, OAuth2AccessTokenResponse>? = null,
                               var onBehalfOfGrantCache : Cache<OnBehalfOfGrantRequest, OAuth2AccessTokenResponse>? = null) {



    fun getAccessToken(clientProperties : ClientProperties?) : OAuth2AccessTokenResponse {
        if (clientProperties == null) {
            throw OAuth2ClientException("ClientProperties cannot be null")
        }
        log.debug("getting access_token for grant={}", clientProperties.grantType)
        return if (isGrantType(clientProperties, OAuth2GrantType.JWT_BEARER)) {
            executeOnBehalfOf(clientProperties)
        }
        else if (isGrantType(clientProperties, OAuth2GrantType.CLIENT_CREDENTIALS)) {
            executeClientCredentials(clientProperties)
        }
        else if (isGrantType(clientProperties, OAuth2GrantType.TOKEN_EXCHANGE)) {
            executeTokenExchange(clientProperties)
        }
        else {
            throw OAuth2ClientException(String.format("invalid grant-type=%s from OAuth2ClientConfig.OAuth2Client" +
                ". grant-type not in supported grant-types (%s)",
                clientProperties.grantType.value(), SUPPORTED_GRANT_TYPES))
        }
    }

    private fun executeOnBehalfOf(clientProperties : ClientProperties) : OAuth2AccessTokenResponse {
        val grantRequest = onBehalfOfGrantRequest(clientProperties)
        return getFromCacheIfEnabled(grantRequest, onBehalfOfGrantCache) { grantRequest : OnBehalfOfGrantRequest ->
            onBehalfOfTokenClient.getTokenResponse(grantRequest)
        }
    }

    private fun executeTokenExchange(clientProperties : ClientProperties) : OAuth2AccessTokenResponse {
        val grantRequest = tokenExchangeGrantRequest(clientProperties)
        return getFromCacheIfEnabled(grantRequest, exchangeGrantCache) { req : TokenExchangeGrantRequest ->
            tokenExchangeClient.getTokenResponse(req)
        }
    }

    private fun executeClientCredentials(clientProperties : ClientProperties) : OAuth2AccessTokenResponse {
        val grantRequest = ClientCredentialsGrantRequest(clientProperties)
        return getFromCacheIfEnabled(grantRequest,
            clientCredentialsGrantCache) { grantRequest : ClientCredentialsGrantRequest -> clientCredentialsTokenClient.getTokenResponse(grantRequest) }
    }

    private fun isGrantType(clientProperties : ClientProperties,
                            grantType : OAuth2GrantType) : Boolean {
        return Optional.ofNullable(clientProperties)
            .filter { client : ClientProperties -> client.grantType == grantType }
            .isPresent
    }

    private fun tokenExchangeGrantRequest(clientProperties : ClientProperties) : TokenExchangeGrantRequest {
        return TokenExchangeGrantRequest(clientProperties, tokenResolver.token()
            .orElseThrow {
                OAuth2ClientException("no authenticated jwt token found in validation context, " +
                    "cannot do token exchange")
            })
    }

    private fun onBehalfOfGrantRequest(clientProperties : ClientProperties) : OnBehalfOfGrantRequest {
        return OnBehalfOfGrantRequest(clientProperties, tokenResolver.token()
            .orElseThrow {
                OAuth2ClientException("no authenticated jwt token found in validation context, " +
                    "cannot do on-behalf-of")
            })
    }

    override fun toString() : String {
        return javaClass.getSimpleName() + " [" +
            "                            clientCredentialsGrantCache=" + clientCredentialsGrantCache +
            ",                             onBehalfOfGrantCache=" + onBehalfOfGrantCache +
            ",                             tokenExchangeClient=" + tokenExchangeClient +
            ",                             tokenResolver=" + tokenResolver +
            ",                             onBehalfOfTokenClient=" + onBehalfOfTokenClient +
            ",                             clientCredentialsTokenClient=" + clientCredentialsTokenClient +
            ",                             exchangeGrantCache=" + exchangeGrantCache +
            "]"
    }

    companion object {

        private val SUPPORTED_GRANT_TYPES = Arrays.asList(
            OAuth2GrantType.JWT_BEARER,
            OAuth2GrantType.CLIENT_CREDENTIALS,
            OAuth2GrantType.TOKEN_EXCHANGE
                                                         )
        private val log = LoggerFactory.getLogger(OAuth2AccessTokenService::class.java)
        private fun <T : AbstractOAuth2GrantRequest?> getFromCacheIfEnabled(
            grantRequest : T,
            cache : Cache<T, OAuth2AccessTokenResponse>?,
            accessTokenResponseClient : Function<T, OAuth2AccessTokenResponse>
                                                                           ) : OAuth2AccessTokenResponse {
            return if (cache != null) {
                log.debug("cache is enabled so attempt to get from cache or update cache if not present.")
                cache[grantRequest, accessTokenResponseClient]
            }
            else {
                log.debug("cache is not set, invoke client directly")
                accessTokenResponseClient.apply(grantRequest)
            }
        }
    }
}