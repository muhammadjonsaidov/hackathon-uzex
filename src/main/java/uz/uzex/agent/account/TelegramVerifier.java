package uz.uzex.agent.account;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Checks the ID token from Telegram's OpenID Connect login: signature against Telegram's published
 * keys, then issuer, audience, expiry and the nonce this browser was given.
 */
@Component
public class TelegramVerifier {

    @ConfigurationProperties("telegram")
    public record Properties(String clientId, String jwksUri) {}

    public record Identity(long telegramId, String name, String username) {}

    public static class InvalidTokenException extends Exception {
        public InvalidTokenException(String message) {
            super(message);
        }
    }

    private static final String ISSUER = "https://oauth.telegram.org";

    private final String clientId;
    private final DefaultJWTProcessor<SecurityContext> processor;

    @Autowired
    public TelegramVerifier(Properties properties) throws MalformedURLException {
        this(properties.clientId(), JWKSourceBuilder.create(URI.create(properties.jwksUri()).toURL()).build());
    }

    TelegramVerifier(String clientId, JWKSource<SecurityContext> keys) {
        this.clientId = clientId;
        this.processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256), keys));
        if (configured()) {
            processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                    clientId,
                    new JWTClaimsSet.Builder().issuer(ISSUER).build(),
                    Set.of("exp", "iat")));
        }
    }

    public boolean configured() {
        return clientId != null && !clientId.isBlank();
    }

    public String clientId() {
        return clientId;
    }

    public Identity verify(String idToken, String expectedNonce) throws InvalidTokenException {
        if (!configured()) {
            throw new InvalidTokenException("Telegram login is not configured");
        }
        if (idToken == null || expectedNonce == null || expectedNonce.isBlank()) {
            throw new InvalidTokenException("Missing token or nonce");
        }
        try {
            JWTClaimsSet claims = processor.process(idToken, null);
            if (!expectedNonce.equals(claims.getStringClaim("nonce"))) {
                throw new InvalidTokenException("Nonce does not match");
            }
            Long id = claims.getLongClaim("id");
            if (id == null) {
                throw new InvalidTokenException("Token has no Telegram user id");
            }
            return new Identity(id, claims.getStringClaim("name"), claims.getStringClaim("preferred_username"));
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new InvalidTokenException(e.getMessage());
        }
    }
}
