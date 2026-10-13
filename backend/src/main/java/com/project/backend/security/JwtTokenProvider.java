package com.project.backend.security;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.project.backend.user.AppUserRepository;

@Component
public class JwtTokenProvider {

    private final JwtEncoder jwtEncoder;
    private final long expirationMs;
    private final AppUserRepository userRepository;

    public JwtTokenProvider(JwtEncoder jwtEncoder, @Value("${jwt.expiration-ms}") long expirationMs,
            AppUserRepository userRepository) {
        this.jwtEncoder = jwtEncoder;
        this.expirationMs = expirationMs;
        this.userRepository = userRepository;
    }

    public String createToken(String email) {
        var user = userRepository.findByEmail(email).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(email)
                .claim("userId", user.getId())
                .issuedAt(now)
                .expiresAt(now.plusMillis(expirationMs))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
