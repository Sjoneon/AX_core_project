package com.project.backend.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.project.backend.auth.dto.LoginRequest;
import com.project.backend.auth.dto.LoginResponse;
import com.project.backend.auth.dto.SignupRequest;
import com.project.backend.auth.dto.SignupResponse;
import com.project.backend.conversation.ChatMessageRepository;
import com.project.backend.conversation.ConversationRepository;
import com.project.backend.product.UserProductRepository;
import com.project.backend.security.JwtTokenProvider;
import com.project.backend.user.AppUser;
import com.project.backend.user.AppUserRepository;

@Service
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserProductRepository productRepository;
    private final ChatMessageRepository messageRepository;
    private final ConversationRepository conversationRepository;

    public AuthService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager, JwtTokenProvider jwtTokenProvider,
            UserProductRepository productRepository, ChatMessageRepository messageRepository,
            ConversationRepository conversationRepository) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.productRepository = productRepository;
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        checkPasswordLength(request.password());
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (appUserRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
        }

        AppUser user = new AppUser(email, passwordEncoder.encode(request.password()), "USER");
        try {
            return SignupResponse.from(appUserRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
        }
    }

    public LoginResponse login(LoginRequest request) {
        checkPasswordLength(request.password());
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password()));
        return new LoginResponse(jwtTokenProvider.createToken(email), email);
    }

    @Transactional
    public void deleteAccount(String email) {
        var user = appUserRepository.findByEmail(email).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."));
        productRepository.deleteAllByUserId(user.getId());
        messageRepository.deleteAllByConversationUserId(user.getId());
        conversationRepository.deleteAllByUserId(user.getId());
        appUserRepository.delete(user);
    }

    private void checkPasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 72바이트 이하여야 합니다.");
        }
    }
}
