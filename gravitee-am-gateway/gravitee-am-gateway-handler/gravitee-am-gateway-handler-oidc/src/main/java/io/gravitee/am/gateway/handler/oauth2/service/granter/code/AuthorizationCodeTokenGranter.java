/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.am.gateway.handler.oauth2.service.granter.code;

import io.gravitee.am.common.oauth2.CodeChallengeMethod;
import io.gravitee.am.common.oauth2.GrantType;
import io.gravitee.am.common.oauth2.Parameters;
import io.gravitee.am.common.oauth2.exception.InvalidRequestException;
import io.gravitee.am.gateway.handler.common.auth.UserAuthenticationManager;
import io.gravitee.am.gateway.handler.oauth2.exception.InvalidGrantException;
import io.gravitee.am.gateway.handler.oauth2.service.code.AuthorizationCodeService;
import io.gravitee.am.gateway.handler.oauth2.service.granter.AbstractTokenGranter;
import io.gravitee.am.gateway.handler.oauth2.service.pkce.PKCEUtils;
import io.gravitee.am.gateway.handler.oauth2.service.request.TokenRequest;
import io.gravitee.am.gateway.handler.oauth2.service.request.TokenRequestResolver;
import io.gravitee.am.gateway.handler.oauth2.service.token.TokenService;
import io.gravitee.am.model.Client;
import io.gravitee.am.model.User;
import io.gravitee.am.repository.oauth2.model.AuthorizationCode;
import io.gravitee.common.util.MultiValueMap;
import io.reactivex.Maybe;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of the Authorization Code Grant Flow
 * See <a href="https://tools.ietf.org/html/rfc6749#page-24"></a>
 *
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author GraviteeSource Team
 */
public class AuthorizationCodeTokenGranter extends AbstractTokenGranter {

    private final Logger logger = LoggerFactory.getLogger(AuthorizationCodeTokenGranter.class);

    private AuthorizationCodeService authorizationCodeService;

    private UserAuthenticationManager userAuthenticationManager;

    public AuthorizationCodeTokenGranter() {
        super(GrantType.AUTHORIZATION_CODE);
    }

    public AuthorizationCodeTokenGranter(TokenRequestResolver tokenRequestResolver, TokenService tokenService, AuthorizationCodeService authorizationCodeService, UserAuthenticationManager userAuthenticationManager) {
        this();
        setTokenRequestResolver(tokenRequestResolver);
        setTokenService(tokenService);
        this.authorizationCodeService = authorizationCodeService;
        this.userAuthenticationManager = userAuthenticationManager;
    }

    @Override
    protected Single<TokenRequest> parseRequest(TokenRequest tokenRequest, Client client) {
        MultiValueMap<String, String> parameters = tokenRequest.getRequestParameters();
        String code = parameters.getFirst(Parameters.CODE);

        if (code == null || code.isEmpty()) {
            return Single.error(new InvalidRequestException("Missing parameter: code"));
        }

        return super.parseRequest(tokenRequest, client)
                .flatMap(tokenRequest1 -> authorizationCodeService.remove(code, client)
                        .map(authorizationCode -> {
                            checkRedirectUris(tokenRequest1, authorizationCode);
                            checkPCE(tokenRequest1, authorizationCode);
                            // set resource owner
                            tokenRequest1.setSubject(authorizationCode.getSubject());
                            // set original scopes
                            tokenRequest1.setScopes(authorizationCode.getScopes());
                            // set authorization code initial request parameters (step1 of authorization code flow)
                            if (authorizationCode.getRequestParameters() != null) {
                                authorizationCode.getRequestParameters().forEach((key, value) -> tokenRequest1.getRequestParameters().putIfAbsent(key, value));
                            }
                            return tokenRequest1;
                        }).toSingle());
    }

    @Override
    protected Maybe<User> resolveResourceOwner(TokenRequest tokenRequest, Client client) {
        return userAuthenticationManager.loadUserByUsername(tokenRequest.getSubject())
                .onErrorResumeNext(ex -> { return Maybe.error(new InvalidGrantException()); });
    }

    @Override
    protected Single<TokenRequest> resolveRequest(TokenRequest tokenRequest, Client client, User endUser) {
        // request has already been resolved during step1 of authorization code flow
        return Single.just(tokenRequest);
    }

    private void checkRedirectUris(TokenRequest tokenRequest, AuthorizationCode authorizationCode) {
        String redirectUri = tokenRequest.getRequestParameters().getFirst(Parameters.REDIRECT_URI);

        // This might be null, if the authorization was done without the redirect_uri parameter
        // https://tools.ietf.org/html/rfc6749#section-4.1.3 (4.1.3. Access Token Request); if provided
        // their values MUST be identical
        String redirectUriApprovalParameter = authorizationCode.getRequestParameters().getFirst(Parameters.REDIRECT_URI);
        if (redirectUriApprovalParameter != null) {
            if (redirectUri == null) {
                throw new InvalidGrantException("Redirect URI is missing");
            }
            if (!redirectUriApprovalParameter.equals(redirectUri)) {
                throw new InvalidGrantException("Redirect URI mismatch.");
            }
        }
    }

    /**
     * // https://tools.ietf.org/html/rfc7636#section-4.6
     * @param tokenRequest
     * @param authorizationCode
     */
    private void checkPCE(TokenRequest tokenRequest, AuthorizationCode authorizationCode) {
        String codeVerifier = tokenRequest.getRequestParameters().getFirst(Parameters.CODE_VERIFIER);
        MultiValueMap<String, String> parameters = authorizationCode.getRequestParameters();

        String codeChallenge = parameters.getFirst(Parameters.CODE_CHALLENGE);
        String codeChallengeMethod = parameters.getFirst(Parameters.CODE_CHALLENGE_METHOD);

        if (codeChallenge != null && codeVerifier == null) {
            logger.debug("PKCE code_verifier parameter is missing, even if a code_challenge was initially defined");
            throw new InvalidGrantException("Missing parameter: code_verifier");
        }

        if (codeChallenge != null) {
            // Check that code challenge is valid
            if (!PKCEUtils.validCodeVerifier(codeVerifier)) {
                logger.debug("PKCE code_verifier is not valid");
                throw new InvalidGrantException("Invalid parameter: code_verifier");
            }

            // By default, assume a plain code_challenge_method
            String encodedCodeVerifier = codeVerifier;

            // Otherwise, generate is using s256
            if (CodeChallengeMethod.S256.equalsIgnoreCase(codeChallengeMethod)) {
                try {
                    encodedCodeVerifier = PKCEUtils.getS256CodeChallenge(codeVerifier);
                } catch (Exception ex) {
                    logger.error("Not able to generate the codeChallenge from the given code verifier according to S256 algorithm");
                    throw new InvalidGrantException("Not supported algorithm");
                }
            }

            if (! codeChallenge.equals(encodedCodeVerifier)) {
                throw new InvalidGrantException("Invalid code_verifier");
            }
        }
    }
}