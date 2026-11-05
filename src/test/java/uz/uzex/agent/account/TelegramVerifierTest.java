package uz.uzex.agent.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uz.uzex.agent.account.TelegramVerifier.InvalidTokenException;

class TelegramVerifierTest {

    private static final String CLIENT_ID = "123456789";
    private static final String NONCE = "nonce-for-this-browser";

    private static RSAKey telegramKey;
    private static RSAKey otherKey;
    private static TelegramVerifier verifier;

    @BeforeAll
    static void keys() throws Exception {
        telegramKey = new RSAKeyGenerator(2048).keyID("telegram").keyUse(KeyUse.SIGNATURE).generate();
        otherKey = new RSAKeyGenerator(2048).keyID("telegram").keyUse(KeyUse.SIGNATURE).generate();
        verifier = new TelegramVerifier(CLIENT_ID, new ImmutableJWKSet<>(new JWKSet(telegramKey.toPublicJWK())));
    }

    private static JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
                .issuer("https://oauth.telegram.org")
                .audience(CLIENT_ID)
                .subject("1234")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .claim("id", 987654321L)
                .claim("name", "John Doe")
                .claim("preferred_username", "johndoe")
                .claim("nonce", NONCE);
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("telegram").build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void acceptsAValidToken() throws Exception {
        TelegramVerifier.Identity identity = verifier.verify(sign(validClaims().build(), telegramKey), NONCE);
        assertThat(identity.telegramId()).isEqualTo(987654321L);
        assertThat(identity.name()).isEqualTo("John Doe");
        assertThat(identity.username()).isEqualTo("johndoe");
    }

    @Test
    void rejectsATokenSignedByAnotherKey() throws Exception {
        String token = sign(validClaims().build(), otherKey);
        assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        String token = sign(validClaims().expirationTime(Date.from(Instant.now().minusSeconds(7200))).build(), telegramKey);
        assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsATokenForAnotherBot() throws Exception {
        String token = sign(validClaims().audience("999").build(), telegramKey);
        assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsATokenFromAnotherIssuer() throws Exception {
        String token = sign(validClaims().issuer("https://evil.example").build(), telegramKey);
        assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAReplayedNonce() throws Exception {
        String token = sign(validClaims().build(), telegramKey);
        assertThatThrownBy(() -> verifier.verify(token, "a-different-nonce")).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> verifier.verify(token, null)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAnUnsignedToken() {
        String token = new PlainJWT(validClaims().build()).serialize();
        assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsATokenSignedWithTheAlgorithmSwapTrick() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), validClaims().build());
        jwt.sign(new MACSigner(new byte[32]));
        String token = jwt.serialize();
        assertThatThrownBy(() -> verifier.verify(token, NONCE)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> verifier.verify("not-a-token", NONCE)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void refusesEverythingWhenNotConfigured() throws Exception {
        TelegramVerifier off = new TelegramVerifier("", new ImmutableJWKSet<>(new JWKSet(telegramKey.toPublicJWK())));
        assertThat(off.configured()).isFalse();
        String token = sign(validClaims().build(), telegramKey);
        assertThatThrownBy(() -> off.verify(token, NONCE)).isInstanceOf(InvalidTokenException.class);
    }
}
