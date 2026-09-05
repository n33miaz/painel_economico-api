package br.com.economize.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

@Component
public class JwtUtil {

    /**
     * Marca do desafio de segundo fator. Um token com esta claim NÃO é sessão:
     * o filtro de autenticação o recusa, e só o endpoint do segundo passo o
     * aceita. Sem essa separação, o desafio emitido no primeiro passo seria um
     * token válido para a API inteira — o segundo fator viraria enfeite.
     */
    public static final String MFA_PENDING_CLAIM = "mfa_pending";

    /**
     * Cinco minutos: tempo de pegar o celular, abrir o autenticador e digitar.
     * Mais do que isso é janela viva demais para um desafio que já passou pela
     * senha e só espera seis dígitos.
     */
    private static final long MFA_CHALLENGE_MILLIS = 5 * 60 * 1000L;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expirationTime;

    @Value("${jwt.issuer:economize}")
    private String issuer;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(String email) {
        return Jwts.builder()
                .issuer(issuer)
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(getSigningKey())
                .compact();
    }

    /** Desafio de segundo fator: prova que a senha passou, e não abre mais nada. */
    public String generateMfaChallenge(String email) {
        return Jwts.builder()
                .issuer(issuer)
                .subject(email)
                .claims(Map.of(MFA_PENDING_CLAIM, true))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + MFA_CHALLENGE_MILLIS))
                .signWith(getSigningKey())
                .compact();
    }

    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractClaims(token);
            // desafio de MFA não é sessão: quem pergunta "este token vale?"
            // está sempre perguntando por acesso à API
            if (Boolean.TRUE.equals(claims.get(MFA_PENDING_CLAIM, Boolean.class))) return false;
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * E-mail dentro de um desafio de MFA válido, ou {@code null}. Recusa token
     * de sessão comum: usar a sessão de alguém como desafio deixaria o segundo
     * passo aceitar quem nunca fez o primeiro.
     */
    public String emailFromMfaChallenge(String token) {
        try {
            Claims claims = extractClaims(token);
            if (!Boolean.TRUE.equals(claims.get(MFA_PENDING_CLAIM, Boolean.class))) return null;
            if (claims.getExpiration().before(new Date())) return null;
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    public String extractEmail(String token) {
        return extractClaims(token).getSubject();
    }
}
