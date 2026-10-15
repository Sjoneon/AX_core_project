package com.project.backend;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import com.jayway.jsonpath.JsonPath;

import com.project.backend.security.JwtTokenProvider;
import com.project.backend.conversation.ChatMessageRepository;
import com.project.backend.conversation.ConversationRepository;
import com.project.backend.conversation.ProductCategory;
import com.project.backend.user.AppUser;
import com.project.backend.user.AppUserRepository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.backend.conversation.Conversation;

import com.project.backend.conversation.ChatMessage;

import com.project.backend.product.UserProductRepository;

import com.project.backend.rag.ManualCatalog;

import com.project.backend.rag.RagSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest(
		useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
		properties = {
                "homecare.agent.enabled=false",
                "spring.ai.openai.api-key=test-key",
                "spring.ai.openai.chat.model=gpt-4o-mini",
                "spring.datasource.url=${test.datasource.url:jdbc:h2:mem:backend_auth_tests;MODE=MySQL;DB_CLOSE_DELAY=-1}",
                "spring.datasource.driver-class-name=${test.datasource.driver-class-name:org.h2.Driver}",
                "spring.datasource.username=${test.datasource.username:sa}",
                "spring.datasource.password=${test.datasource.password:}",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "jwt.secret=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
                // One user sends every request in this class; ChatRateLimiterTests covers the real limits.
                "homecare.rate-limit.chat.burst=1000",
                "homecare.rate-limit.chat.per-minute=1000",
                "homecare.rate-limit.chat.per-day=100000"
        })
@AutoConfigureMockMvc
class BackendApplicationTests {

	private static final AtomicInteger upstreamStatus = new AtomicInteger(200);
	private static final AtomicReference<String> upstreamBody = new AtomicReference<>();
	private static final AtomicReference<String> requestBody = new AtomicReference<>();
	private static final AtomicReference<String> authorization = new AtomicReference<>();
    private static final AtomicInteger embeddingStatus = new AtomicInteger(200);
    private static final AtomicReference<String> embeddingRequest = new AtomicReference<>();
	private static HttpServer upstream;

	@Autowired
	private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired private UserProductRepository productRepository;
    @Autowired private ManualCatalog manualCatalog;
    @Autowired private com.project.backend.mcp.audit.ToolCallLogRepository toolCallRepository;
    @Autowired private com.project.backend.mcp.consultation.ConsultationService consultations;

    private String accessToken;

	@DynamicPropertySource
	static void configureUpstream(DynamicPropertyRegistry registry) throws IOException {
		upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		upstream.createContext("/v1/chat/completions", exchange -> {
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] body = upstreamBody.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(upstreamStatus.get(), body.length);
			try (var output = exchange.getResponseBody()) {
				output.write(body);
			}
		});

        upstream.createContext("/v1/embeddings", exchange -> {
            String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            embeddingRequest.set(input);
            List<String> texts = JsonPath.read(input, "$.input");
            var data = new ArrayList<String>();
            for (int i = 0; i < texts.size(); i++) {
                data.add("{\"object\":\"embedding\",\"index\":" + i + ",\"embedding\":"
                        + Arrays.toString(testEmbedding(texts.get(i))) + "}");
            }
            String response = embeddingStatus.get() == 200
                    ? "{\"object\":\"list\",\"data\":[" + String.join(",", data)
                            + "],\"model\":\"text-embedding-3-small\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}"
                    : "{\"error\":{\"message\":\"embedding upstream secret\"}}";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(embeddingStatus.get(), body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
		upstream.start();
		registry.add("spring.ai.openai.base-url",
				() -> "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1");
	}

	@BeforeEach
	void resetUpstream() {
        toolCallRepository.deleteAll();
        productRepository.deleteAll();
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        appUserRepository.deleteAll();
        appUserRepository.save(new AppUser("chat-test@example.com", "unused-test-hash", "USER"));
        accessToken = jwtTokenProvider.createToken("chat-test@example.com");
		upstreamStatus.set(200);
		upstreamBody.set(completion("안녕하세요!"));
		requestBody.set(null);
		authorization.set(null);
        embeddingStatus.set(200);
        embeddingRequest.set(null);
	}

	@AfterAll
	static void stopUpstream() {
		if (upstream != null) {
			upstream.stop(0);
		}
	}

	@Test
	void chatReturnsReplyFromSpringAi() throws Exception {
		mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"안녕하세요 {name}\",\"category\":\"WASHER\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reply").value("안녕하세요!"));

		assertThat(requestBody.get()).contains("gpt-4o-mini", "안녕하세요 {name}", "\"system\"", "\"user\"");
		assertThat(authorization.get()).isEqualTo("Bearer test-key");
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"message\":null}", "{\"message\":\"\"}", "{\"message\":\"   \"}"})
	void invalidMessageDoesNotCallLlm(String body) throws Exception {
		mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("message는 필수입니다."));

		assertThat(requestBody.get()).isNull();
	}

	@Test
	void tooLongMessageDoesNotCallLlm() throws Exception {
		mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"" + "a".repeat(4001) + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("message는 4000자 이하여야 합니다."));

		assertThat(requestBody.get()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"{", ""})
	void invalidJsonDoesNotCallLlm(String body) throws Exception {
		mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("올바른 JSON 요청 본문이 필요합니다."));

		assertThat(requestBody.get()).isNull();
	}

	@Test
	void upstreamFailureReturnsSafeError() throws Exception {
		upstreamStatus.set(401);
		upstreamBody.set("{\"error\":{\"message\":\"sensitive upstream detail\",\"type\":\"invalid_request_error\"}}");

		mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"안녕\",\"category\":\"WASHER\"}"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.message").value("AI 응답을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요."));
	}

	@Test
	void emptyReplyReturnsBadGateway() throws Exception {
		upstreamBody.set(completion(""));

		mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"안녕\",\"category\":\"WASHER\"}"))
				.andExpect(status().isBadGateway());
	}

	@Test
	void healthDoesNotCallLlm() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));

		assertThat(requestBody.get()).isNull();
	}


    @Test
    void signupPersistsHashedPasswordAndLoginTokenAllowsChat() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"USER@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist());

        var saved = appUserRepository.findByEmail("user@example.com").orElseThrow();
        assertThat(saved.getPassword()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", saved.getPassword())).isTrue();

        var login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"USER@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andReturn();
        String token = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        var jwt = jwtDecoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo("user@example.com");
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(3600));

        mockMvc.perform(post("/api/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"안녕\",\"category\":\"WASHER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("안녕하세요!"));
    }

    @Test
    void duplicateEmailReturnsConflict() throws Exception {
        String body = "{\"email\":\"user@example.com\",\"password\":\"password123\"}";
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"USER@example.com\",\"password\":\"password456\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 가입된 이메일입니다."));
        assertThat(appUserRepository.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"email\":\"bad-email\",\"password\":\"password123\"}",
            "{\"email\":\"user@example.com\",\"password\":\"short\"}",
            "{\"email\":\"user@example.com\",\"password\":\"        \"}",
            "{}"
    })
    void invalidSignupDoesNotPersistUser(String body) throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString());
        assertThat(appUserRepository.count()).isEqualTo(1);
    }

    @Test
    void multibytePasswordOverBcryptLimitIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"" + "가".repeat(25) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("비밀번호는 72바이트 이하여야 합니다."));
        assertThat(appUserRepository.count()).isEqualTo(1);
    }

    @Test
    void incorrectAndUnknownCredentialsReturnSameError() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());
        for (String email : new String[] { "user@example.com", "unknown@example.com" }) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다."));
        }
    }

    @Test
    void anonymousChatDoesNotCallLlm() throws Exception {
        mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"안녕\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").isString());
        assertThat(requestBody.get()).isNull();
    }

    @Test
    void invalidAndTamperedTokenDoNotCallLlm() throws Exception {
        String[] parts = accessToken.split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        for (String token : new String[] { "invalid", String.join(".", parts) }) {
            mockMvc.perform(post("/api/chat")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\":\"안녕\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").isString());
        }
        assertThat(requestBody.get()).isNull();
    }

    @Test
    void expiredTokenDoesNotCallLlm() throws Exception {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().subject("user@example.com")
                .issuedAt(now.minusSeconds(600)).expiresAt(now.minusSeconds(300)).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        mockMvc.perform(post("/api/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"안녕\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(requestBody.get()).isNull();
    }

    @Test
    void chatPersistsOrderedMessagesAndCanBeReadWithANewLoginToken() throws Exception {
        long id = sendChat("{\"message\":\"첫 질문\",\"category\":\"WASHER\"}", accessToken);
        String newToken = jwtTokenProvider.createToken("chat-test@example.com");

        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].title").value("첫 질문"))
                .andExpect(jsonPath("$[0].createdAt").isString())
                .andExpect(jsonPath("$[0].updatedAt").isString())
                .andExpect(jsonPath("$[0].messages").doesNotExist());
        mockMvc.perform(get("/api/conversations/{id}", id).header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("첫 질문"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].content").value("안녕하세요!"));
    }

    @Test
    void continuationAppendsMessagesAndMovesConversationToTop() throws Exception {
        long first = sendChat("{\"message\":\"첫 대화\",\"category\":\"WASHER\"}", accessToken);
        long second = sendChat("{\"message\":\"새 대화\"}", accessToken);
        assertThat(sendChat("{\"message\":\"추가 질문\",\"conversationId\":" + first + "}", accessToken))
                .isEqualTo(first);

        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(second));
        mockMvc.perform(get("/api/conversations/{id}", first).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(4))
                .andExpect(jsonPath("$.messages[2].content").value("추가 질문"))
                .andExpect(jsonPath("$.messages[3].role").value("assistant"));
        // Preserve the existing independent-question behavior.
        assertThat((Integer) JsonPath.read(requestBody.get(), "$.messages.length()")).isEqualTo(2);
        assertThat(requestBody.get()).doesNotContain("첫 대화");
    }

    @Test
    void otherUserCannotListReadOrAppendToConversation() throws Exception {
        long id = sendChat("{\"message\":\"나의 비공개 질문\"}", accessToken);
        appUserRepository.save(new AppUser("other@example.com", "unused-test-hash", "USER"));
        String otherToken = jwtTokenProvider.createToken("other@example.com");
        requestBody.set(null);

        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/conversations/{id}", id).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("대화를 찾을 수 없습니다."));
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"침입 시도\",\"conversationId\":" + id + "}"))
                .andExpect(status().isNotFound());
        assertThat(requestBody.get()).isNull();
        assertThat(messageRepository.count()).isEqualTo(2);
    }

    @Test
    void missingConversationDoesNotCallLlm() throws Exception {
        mockMvc.perform(get("/api/conversations/999999").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"질문\",\"conversationId\":999999}"))
                .andExpect(status().isNotFound());
        assertThat(requestBody.get()).isNull();
        assertThat(conversationRepository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveConversationIdIsRejected(long id) throws Exception {
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"질문\",\"conversationId\":" + id + "}"))
                .andExpect(status().isBadRequest());
        assertThat(requestBody.get()).isNull();
    }

    @Test
    void anonymousHistoryRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/conversations/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void failedAndEmptyRepliesDoNotPersistNewHistory() throws Exception {
        upstreamStatus.set(500);
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"실패할 질문\",\"category\":\"WASHER\"}"))
                .andExpect(status().isBadGateway());
        upstreamStatus.set(200);
        upstreamBody.set(completion(""));
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"빈 답변 질문\",\"category\":\"WASHER\"}"))
                .andExpect(status().isBadGateway());
        assertThat(conversationRepository.count()).isZero();
        assertThat(messageRepository.count()).isZero();
    }

    @Test
    void failedContinuationKeepsExistingHistoryUnchanged() throws Exception {
        long id = sendChat("{\"message\":\"성공한 질문\",\"category\":\"WASHER\"}", accessToken);
        var previousUpdate = conversationRepository.findById(id).orElseThrow().getUpdatedAt();
        upstreamBody.set(completion(""));
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"실패할 질문\",\"conversationId\":" + id + "}"))
                .andExpect(status().isBadGateway());
        assertThat(messageRepository.count()).isEqualTo(2);
        assertThat(conversationRepository.findById(id).orElseThrow().getUpdatedAt()).isEqualTo(previousUpdate);
    }

    @Test
    void longReplyAndUnicodeTitleAreStoredWithoutTruncatingMessages() throws Exception {
        String message = "😀".repeat(60);
        String reply = "가".repeat(20000);
        upstreamBody.set(completion(reply));
        long id = sendChat("{\"message\":\"" + message + "\",\"category\":\"WASHER\"}", accessToken);
        mockMvc.perform(get("/api/conversations/{id}", id).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("😀".repeat(50)))
                .andExpect(jsonPath("$.messages[0].content").value(message))
                .andExpect(jsonPath("$.messages[1].content").value(reply));
    }

    @ParameterizedTest
    @EnumSource(value = ProductCategory.class, names = "AIR_CONDITIONER", mode = EnumSource.Mode.EXCLUDE)
    void categoryIsPersistedReturnedAndReusedForFollowUpQuestions(ProductCategory category) throws Exception {
        long id = sendChat("{\"message\":\"작동하지 않아요\",\"category\":\"" + category.name() + "\"}", accessToken);
        assertThat(conversationRepository.findById(id).orElseThrow().getCategory()).isEqualTo(category);
        assertThat(requestBody.get()).contains(category.getLabel());

        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value(category.name()));
        mockMvc.perform(get("/api/conversations/{id}", id).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value(category.name()))
                .andExpect(jsonPath("$.messages[0].content").value("작동하지 않아요"));

        sendChat("{\"message\":\"추가로 확인할 내용이 있나요\",\"conversationId\":" + id + "}", accessToken);
        assertThat(requestBody.get()).contains(category.getLabel());
        assertThat(messageRepository.count()).isEqualTo(4);
    }

    @Test
    void changingExistingCategoryDoesNotCallLlmOrAlterHistory() throws Exception {
        long id = sendChat("{\"message\":\"질문\",\"category\":\"WASHER\"}", accessToken);
        requestBody.set(null);
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"질문\",\"category\":\"DRYER\",\"conversationId\":" + id + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("기존 상담의 카테고리는 변경할 수 없습니다."));
        assertThat(requestBody.get()).isNull();
        assertThat(messageRepository.count()).isEqualTo(2);
        assertThat(conversationRepository.findById(id).orElseThrow().getCategory()).isEqualTo(ProductCategory.WASHER);
    }

    @Test
    void unknownCategoryIsRejectedBeforeCallingLlm() throws Exception {
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"질문\",\"category\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest());
        assertThat(requestBody.get()).isNull();
        assertThat(conversationRepository.count()).isZero();
    }

    @Test
    void legacyConversationWithoutCategoryRemainsReadableAndCanContinue() throws Exception {
        long id = sendChat("{\"message\":\"이전 일반 질문\"}", accessToken);
        mockMvc.perform(get("/api/conversations/{id}", id).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.category").isEmpty());
        sendChat("{\"message\":\"추가 질문\",\"category\":null,\"conversationId\":" + id + "}", accessToken);
        assertThat(conversationRepository.findById(id).orElseThrow().getCategory()).isNull();
        assertThat(messageRepository.count()).isEqualTo(4);
    }

    @Test
    void deleteRemovesOnlySelectedConversationAndItsMessages() throws Exception {
        long removed = sendChat("{\"message\":\"삭제할 상담\",\"category\":\"WASHER\"}", accessToken);
        long retained = sendChat("{\"message\":\"유지할 상담\"}", accessToken);
        requestBody.set(null);

        mockMvc.perform(delete("/api/conversations/{id}", removed).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        assertThat(conversationRepository.existsById(removed)).isFalse();
        assertThat(messageRepository.findByConversationIdOrderByIdAsc(removed)).isEmpty();
        assertThat(conversationRepository.existsById(retained)).isTrue();
        assertThat(messageRepository.findByConversationIdOrderByIdAsc(retained)).hasSize(2);
        assertThat(appUserRepository.existsByEmail("chat-test@example.com")).isTrue();
        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(retained));
        mockMvc.perform(get("/api/conversations/{id}", removed).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
        assertThat(requestBody.get()).isNull();
    }

    @Test
    void otherUserCannotDeleteConversation() throws Exception {
        long id = sendChat("{\"message\":\"비공개 상담\"}", accessToken);
        appUserRepository.save(new AppUser("other@example.com", "unused-test-hash", "USER"));
        String otherToken = jwtTokenProvider.createToken("other@example.com");
        mockMvc.perform(delete("/api/conversations/{id}", id).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("대화를 찾을 수 없습니다."));
        assertThat(conversationRepository.existsById(id)).isTrue();
        assertThat(messageRepository.findByConversationIdOrderByIdAsc(id)).hasSize(2);
    }

    @Test
    void missingOrAlreadyDeletedConversationReturnsNotFound() throws Exception {
        mockMvc.perform(delete("/api/conversations/999999").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
        long id = sendChat("{\"message\":\"삭제할 상담\"}", accessToken);
        mockMvc.perform(delete("/api/conversations/{id}", id).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/conversations/{id}", id).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
        requestBody.set(null);
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"후속 질문\",\"conversationId\":" + id + "}"))
                .andExpect(status().isNotFound());
        assertThat(requestBody.get()).isNull();
        assertThat(messageRepository.count()).isZero();
    }

    @Test
    void anonymousAndInvalidTokenCannotDeleteHistory() throws Exception {
        long id = sendChat("{\"message\":\"유지할 상담\"}", accessToken);
        mockMvc.perform(delete("/api/conversations/{id}", id)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/conversations/{id}", id).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        assertThat(conversationRepository.existsById(id)).isTrue();
        assertThat(messageRepository.findByConversationIdOrderByIdAsc(id)).hasSize(2);
    }

    @Test
    void toolApiIsHiddenBehindTheProxyAndToolCallsAreOwnerOnly() throws Exception {
        long id = sendChat("{\"message\":\"유지할 상담\"}", accessToken);
        // Nginx always adds X-Forwarded-For; only the internal MCP container may call /api/tools.
        mockMvc.perform(post("/api/tools/get_product_context").header("Authorization", "Bearer " + accessToken)
                .header("X-Forwarded-For", "203.0.113.7").contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversation_id\":" + id + "}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/conversations/{id}/tool-calls", id).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        appUserRepository.save(new AppUser("other@example.com", "unused-test-hash", "USER"));
        String otherToken = jwtTokenProvider.createToken("other@example.com");
        mockMvc.perform(get("/api/conversations/{id}/tool-calls", id).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/conversations/{id}/tool-calls", id)).andExpect(status().isUnauthorized());
    }

    private long sendChat(String body, String token) throws Exception {
        var result = mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.conversationId")).longValue();
    }

    @ParameterizedTest
    @EnumSource(value = ProductCategory.class, names = "AIR_CONDITIONER", mode = EnumSource.Mode.EXCLUDE)
    void ragErrorCodeUsesOnlySelectedCategoryAndPersistsEvidence(ProductCategory category) throws Exception {
        String prefix = switch (category) {
            case REFRIGERATOR -> "RF";
            case AIR_CONDITIONER -> throw new IllegalArgumentException("Unsupported category");
            case WASHER -> "WS";
            case DRYER -> "DR";
            case DISHWASHER -> "DW";
        };
        String expectedId = "HC-" + prefix + "-001";
        var response = mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + category.getLabel() + " HC-E01이 떠요\",\"category\":\"" + category.name() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0].documentId").value(expectedId))
                .andExpect(jsonPath("$.sources[0].version").value("1.0"))
                .andReturn();
        List<String> categories = JsonPath.read(response.getResponse().getContentAsString(), "$.sources[*].category");
        assertThat(categories).isNotEmpty().allMatch(value -> value.equals(category.name()));
        String system = JsonPath.read(requestBody.get(), "$.messages[0].content");
        assertThat(system).contains("자료에 정보가 없다고", "사용 중단 안내를 우선", "<manual_context>", expectedId);
        String context = system.substring(system.indexOf("<manual_context>"));
        assertThat(context).contains("카테고리: `" + category.name() + "`");
        for (ProductCategory other : ProductCategory.values()) {
            if (other != category) assertThat(context).doesNotContain("카테고리: `" + other.name() + "`");
        }
        long id = ((Number) JsonPath.read(response.getResponse().getContentAsString(), "$.conversationId")).longValue();
        mockMvc.perform(get("/api/conversations/{id}", id).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].sources.length()").value(0))
                .andExpect(jsonPath("$.messages[1].sources[0].documentId").value(expectedId))
                .andExpect(jsonPath("$.messages[1].sources[0].content").value(
                        (String) JsonPath.read(response.getResponse().getContentAsString(), "$.sources[0].content")));
    }

    @Test
    void semanticSearchFindsManualWithoutErrorCode() throws Exception {
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"냉장고 문에 물방울이 맺혀요\",\"category\":\"REFRIGERATOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].documentId").value("HC-RF-003"));
        assertThat(embeddingRequest.get()).contains("물방울", "text-embedding-3-small");
        assertThat(requestBody.get()).contains("마른 천", "결로", "HC-RF-003");
    }

    @Test
    void unknownCodeDoesNotGenerateAnInventedDiagnosis() throws Exception {
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"세탁기 HC-E99가 떠요\",\"category\":\"WASHER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("HC-E99의 의미가 없습니다")))
                .andExpect(jsonPath("$.sources.length()").value(0));
        assertThat(requestBody.get()).isNull();
        assertThat(embeddingRequest.get()).isNull();
        assertThat(messageRepository.count()).isEqualTo(2);
    }

    @Test
    void mismatchedProductAsksForClarification() throws Exception {
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"냉장고 HC-E01이 떠요\",\"category\":\"WASHER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("어떤 제품")))
                .andExpect(jsonPath("$.sources.length()").value(0));
        assertThat(requestBody.get()).isNull();
        assertThat(embeddingRequest.get()).isNull();
    }

    @Test
    void missingCategoryAsksForProductWithoutCallingAi() throws Exception {
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"HC-E01이 떠요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("제품 카테고리")))
                .andExpect(jsonPath("$.sources.length()").value(0));
        assertThat(requestBody.get()).isNull();
        assertThat(embeddingRequest.get()).isNull();
    }

    @Test
    void unsupportedProductWithoutCategoryDoesNotInventInstructions() throws Exception {
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"TV 화면이 안 나와요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("TV 상담 자료가 없습니다")))
                .andExpect(jsonPath("$.sources.length()").value(0));
        assertThat(requestBody.get()).isNull();
        assertThat(embeddingRequest.get()).isNull();
    }

    @Test
    void noSearchResultDoesNotFallBackToGeneralKnowledge() throws Exception {
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"아침 메뉴 추천해주세요\",\"category\":\"WASHER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("근거를 찾지 못했습니다")))
                .andExpect(jsonPath("$.sources.length()").value(0));
        assertThat(requestBody.get()).isNull();
    }

    @Test
    void embeddingFailureDoesNotCallLlmOrSaveHistory() throws Exception {
        embeddingStatus.set(401);
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"세탁기 HC-E01이 떠요\",\"category\":\"WASHER\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("AI 응답을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요."));
        assertThat(requestBody.get()).isNull();
        assertThat(messageRepository.count()).isZero();
        assertThat(conversationRepository.count()).isZero();
        embeddingStatus.set(200);
        sendChat("{\"message\":\"세탁기 HC-E01이 떠요\",\"category\":\"WASHER\"}", accessToken);
    }

    @Test
    void warrantyApiRequiresAuthenticationAndDoesNotCallOpenAi() throws Exception {
        mockMvc.perform(post("/api/warranty/check").contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"WASHER\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/warranty/check").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"WASHER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productWarranty.scope").value("PRODUCT"))
                .andExpect(jsonPath("$.productWarranty.periodStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.partWarranties[0].scope").value("CORE_PART"));
        assertThat(requestBody.get()).isNull();
        assertThat(embeddingRequest.get()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TV", "HEATER"})
    void unsupportedCategoryIsRejectedBeforeModelOrWarrantyCalls(String category) throws Exception {
        for (String path : List.of("/api/chat", "/api/warranty/check")) {
            mockMvc.perform(post(path).header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"category\":\"" + category + "\",\"message\":\"제품 문의\"}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(requestBody.get()).isNull();
        assertThat(embeddingRequest.get()).isNull();
        assertThat(conversationRepository.count()).isZero();
    }

    @Test
    void warrantyCatalogContainsOnlyTheFourSupportedCategories() throws Exception {
        var response = mockMvc.perform(get("/api/warranty/policies")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andReturn();
        List<String> categories = JsonPath.read(response.getResponse().getContentAsString(), "$.policies[*].category");
        assertThat(categories).isNotEmpty();
        assertThat(new java.util.HashSet<>(categories))
                .containsExactlyInAnyOrder("WASHER", "DRYER", "REFRIGERATOR", "DISHWASHER");
        assertThat(requestBody.get()).isNull();
    }

    @Test
    void warrantyApiRejectsFutureDates() throws Exception {
        mockMvc.perform(post("/api/warranty/check").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"WASHER\",\"purchaseDate\":\"2099-01-01\"}"))
                .andExpect(status().isBadRequest());
        assertThat(requestBody.get()).isNull();
    }

    @Test
    void agentCallsWarrantyToolAndPersistsSeparatedResults() throws Exception {
        upstreamBody.set("""
            {"id":"chatcmpl-warranty","object":"chat.completion","created":1,"model":"gpt-4o-mini",
             "choices":[{"index":0,"message":{"role":"assistant","content":null,
             "tool_calls":[{"id":"call-warranty","type":"function","function":{"name":"check_warranty","arguments":"{}"}}]},"finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
            """);
        var response = mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"WASHER\",\"message\":\"제품 보증과 모터 보증이 다른가요?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warranty.productWarranty.periodMonths").value(12))
                .andExpect(jsonPath("$.warranty.partWarranties[0].scope").value("CORE_PART"))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("주요 부품 보증")))
                .andReturn().getResponse().getContentAsString();
        Number conversationId = JsonPath.read(response, "$.conversationId");
        mockMvc.perform(get("/api/conversations/" + conversationId).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[1].warranty.productWarranty.scope").value("PRODUCT"));
        assertThat(embeddingRequest.get()).isNull();
        assertThat(requestBody.get()).contains("check_warranty").doesNotContain("warrantyResult", "AtomicReference");
    }

    @Test
    void inventedWarrantyAnswerIsReplacedByServerPolicy() throws Exception {
        upstreamBody.set(completion("모든 제품은 100년 무상입니다."));
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"WASHER\",\"message\":\"무상 보증 안내\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("100년"))))
                .andExpect(jsonPath("$.warranty.productWarranty.periodMonths").value(12));
    }

    @Test
    void warrantyQuestionWithSmokeShowsSafetyBeforeCalculation() throws Exception {
        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"WASHER\",\"message\":\"연기가 나는데 무상 수리인가요?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("안전 확인이 먼저")))
                .andExpect(jsonPath("$.warranty").isEmpty());
        assertThat(requestBody.get()).isNull();
    }

    // Deterministic vectors test the real VectorStore/filter/prompt pipeline without paid API calls.
    private static float[] testEmbedding(String text) {
        float[] vector = new float[18];
        var document = Pattern.compile("^HC-(RF|WS|DR|DW)-(00[1-4])").matcher(text);
        if (document.find()) {
            int group = List.of("RF", "WS", "DR", "DW").indexOf(document.group(1));
            vector[group * 4 + Integer.parseInt(document.group(2)) - 1] = 1;
            vector[16] = 1;
        } else if (text.contains("물방울")) {
            vector[2] = 1;
        } else if (text.contains("아침 메뉴")) {
            vector[17] = 1;
        } else {
            vector[16] = 1;
        }
        return vector;
    }

	private static String completion(String reply) {
		return """
				{"id":"chatcmpl-test","object":"chat.completion","created":1,"model":"gpt-4o-mini",
				"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
				"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
				""".formatted(reply);
	}



        @Test
        void accountCheckReturnsOnlyAuthenticatedEmailWithoutRenewingToken() throws Exception {
                var before = jwtDecoder.decode(accessToken);
                for (int attempt = 0; attempt < 2; attempt++) {
                        mockMvc.perform(get("/api/auth/me").param("email", "other@example.com")
                                        .header("Authorization", "Bearer " + accessToken))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.email").value("chat-test@example.com"))
                                        .andExpect(jsonPath("$.accessToken").doesNotExist())
                                        .andExpect(jsonPath("$.password").doesNotExist())
                                        .andExpect(jsonPath("$.userId").doesNotExist())
                                        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
                }
                assertThat(jwtDecoder.decode(accessToken).getExpiresAt()).isEqualTo(before.getExpiresAt());
                assertThat(requestBody.get()).isNull();
                assertThat(embeddingRequest.get()).isNull();
        }

        @Test
        void withdrawalDeletesOnlyAuthenticatedUsersAccountAndRelatedData() throws Exception {
                var owner = appUserRepository.findByEmail("chat-test@example.com").orElseThrow();
                long product = createProduct("{\"category\":\"WASHER\"}");
                createProduct("{\"category\":\"REFRIGERATOR\",\"address\":\"검증용 주소\"}");
                var conversation = conversationRepository.save(new Conversation(owner, "탈퇴할 회원의 상담", ProductCategory.WASHER));
                var legacy = conversationRepository.save(new Conversation(owner, "과거 상담", ProductCategory.AIR_CONDITIONER));
                var answer = new ChatMessage(conversation, "assistant", "답변");
                answer.setSources(List.of(new RagSource("HC-WS-001", "검색 근거", ProductCategory.WASHER,
                                "1.0", "washer.md", "근거 본문")));
                messageRepository.saveAll(List.of(new ChatMessage(conversation, "user", "질문"), answer,
                                new ChatMessage(legacy, "assistant", "과거 답변")));
                var other = appUserRepository.save(new AppUser("other@example.com", "unused", "USER"));
                String ownerToken = accessToken;
                accessToken = jwtTokenProvider.createToken(other.getEmail());
                long otherProduct = createProduct("{\"category\":\"DRYER\"}");
                var otherConversation = conversationRepository.save(new Conversation(other, "다른 회원 상담", ProductCategory.DRYER));
                var otherMessage = messageRepository.save(new ChatMessage(otherConversation, "user", "유지할 질문"));

                var result = mockMvc.perform(delete("/api/auth/me").header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"other@example.com\"}"))
                                .andExpect(status().isNoContent()).andReturn();
                assertThat(result.getResponse().getContentAsString()).isEmpty();
                assertThat(appUserRepository.existsById(owner.getId())).isFalse();
                assertThat(productRepository.existsById(product)).isFalse();
                assertThat(conversationRepository.existsById(conversation.getId())).isFalse();
                assertThat(conversationRepository.existsById(legacy.getId())).isFalse();
                assertThat(appUserRepository.count()).isEqualTo(1);
                assertThat(productRepository.count()).isEqualTo(1);
                assertThat(conversationRepository.count()).isEqualTo(1);
                assertThat(messageRepository.count()).isEqualTo(1);
                assertThat(appUserRepository.existsById(other.getId())).isTrue();
                assertThat(messageRepository.existsById(otherMessage.getId())).isTrue();
                mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + accessToken))
                                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(otherProduct));
                mockMvc.perform(get("/api/conversations/{id}", otherConversation.getId()).header("Authorization", "Bearer " + accessToken))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].content").value("유지할 질문"));
        }

        @Test
        void withdrawalInvalidatesAllOldTokensEvenAfterRegisteringSameEmailAgain() throws Exception {
                long oldUserId = appUserRepository.findByEmail("chat-test@example.com").orElseThrow().getId();
                String secondToken = jwtTokenProvider.createToken("chat-test@example.com");
                mockMvc.perform(delete("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                                .andExpect(status().isNoContent());
                assertThat(appUserRepository.count()).isZero();
                for (String token : new String[]{accessToken, secondToken}) {
                        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                                        .andExpect(status().isUnauthorized());
                        mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + token))
                                        .andExpect(status().isUnauthorized());
                        mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + token))
                                        .andExpect(status().isUnauthorized());
                        mockMvc.perform(delete("/api/auth/me").header("Authorization", "Bearer " + token))
                                        .andExpect(status().isUnauthorized());
                }
                String body = "{\"email\":\"chat-test@example.com\",\"password\":\"password123\"}";
                mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isUnauthorized());
                mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isCreated());
                long newUserId = appUserRepository.findByEmail("chat-test@example.com").orElseThrow().getId();
                assertThat(newUserId).isNotEqualTo(oldUserId);
                var login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isOk()).andReturn();
                String newToken = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
                for (String token : new String[]{accessToken, secondToken}) {
                        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                                        .andExpect(status().isUnauthorized());
                        mockMvc.perform(post("/api/products").header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"WASHER\"}"))
                                        .andExpect(status().isUnauthorized());
                        mockMvc.perform(delete("/api/auth/me").header("Authorization", "Bearer " + token))
                                        .andExpect(status().isUnauthorized());
                }
                assertThat(appUserRepository.existsById(newUserId)).isTrue();
                mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + newToken))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("chat-test@example.com"));
                mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + newToken))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
                mockMvc.perform(post("/api/products").header("Authorization", "Bearer " + newToken)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"WASHER\"}"))
                                .andExpect(status().isCreated());
        }

        @Test
        void withdrawalRequiresValidAuthenticationAndMatchingAccountId() throws Exception {
                long product = createProduct("{\"category\":\"WASHER\"}");
                Instant now = Instant.now();
                var header = JwsHeader.with(MacAlgorithm.HS256).build();
                var expiredClaims = JwtClaimsSet.builder().subject("chat-test@example.com")
                                .claim("userId", appUserRepository.findByEmail("chat-test@example.com").orElseThrow().getId())
                                .issuedAt(now.minusSeconds(600)).expiresAt(now.minusSeconds(300)).build();
                String expired = jwtEncoder.encode(JwtEncoderParameters.from(header, expiredClaims)).getTokenValue();
                var wrongIdClaims = JwtClaimsSet.builder().subject("chat-test@example.com").claim("userId", Long.MAX_VALUE)
                                .issuedAt(now).expiresAt(now.plusSeconds(600)).build();
                String wrongId = jwtEncoder.encode(JwtEncoderParameters.from(header, wrongIdClaims)).getTokenValue();
                var missingIdClaims = JwtClaimsSet.builder().subject("chat-test@example.com")
                                .issuedAt(now).expiresAt(now.plusSeconds(600)).build();
                String missingId = jwtEncoder.encode(JwtEncoderParameters.from(header, missingIdClaims)).getTokenValue();
                mockMvc.perform(delete("/api/auth/me")).andExpect(status().isUnauthorized());
                mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
                for (String token : new String[]{"", "invalid", expired, wrongId, missingId}) {
                        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                                        .andExpect(status().isUnauthorized());
                        mockMvc.perform(delete("/api/auth/me").header("Authorization", "Bearer " + token))
                                        .andExpect(status().isUnauthorized());
                }
                assertThat(appUserRepository.count()).isEqualTo(1);
                assertThat(productRepository.existsById(product)).isTrue();
        }

        @Test
        void productsPersistAcrossLoginAndSortByRegistration() throws Exception {
                mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"product-login@example.com\",\"password\":\"password123\"}"))
                                .andExpect(status().isCreated());
                var login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"product-login@example.com\",\"password\":\"password123\"}"))
                                .andExpect(status().isOk()).andReturn();
                accessToken = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
                mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + accessToken))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
                long first = createProduct("{\"category\":\"WASHER\"}");
                long second = createProduct(
                                """
                                                {"category":"REFRIGERATOR","name":"  주방 냉장고  ","model":"  RF-1  ",
                                                 "purchaseDate":"1950-01-01","postcode":"01234","address":"  서울시  ","addressDetail":"  101호  "}
                                                """);
                mockMvc.perform(put("/api/products/{id}", first).header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"category\":\"DRYER\",\"name\":\"수정한 제품\"}"))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.category").value("DRYER"));
                var relogin = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"product-login@example.com\",\"password\":\"password123\"}"))
                                .andExpect(status().isOk()).andReturn();
                String token = JsonPath.read(relogin.getResponse().getContentAsString(), "$.accessToken");
                mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                                .andExpect(jsonPath("$[0].id").value(second))
                                .andExpect(jsonPath("$[1].id").value(first))
                                .andExpect(jsonPath("$[0].name").value("주방 냉장고"))
                                .andExpect(jsonPath("$[0].model").value("RF-1"))
                                .andExpect(jsonPath("$[0].purchaseDate").value("1950-01-01"))
                                .andExpect(jsonPath("$[0].postcode").value("01234"))
                                .andExpect(jsonPath("$[0].address").value("서울시"))
                                .andExpect(jsonPath("$[0].addressDetail").value("101호"))
                                .andExpect(jsonPath("$[0].createdAt").isString())
                                .andExpect(jsonPath("$[0].updatedAt").isString())
                                .andExpect(jsonPath("$[1].category").value("DRYER"));
        }

        private long createProduct(String body) throws Exception {
                var result = mockMvc.perform(post("/api/products").header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isCreated()).andReturn();
                return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        }

        @Test
        void productUpdatesCanClearOptionalInformation() throws Exception {
                long id = createProduct("""
                                {"category":"REFRIGERATOR","name":"주방","model":"RF-1","purchaseDate":"2024-02-29",
                                 "postcode":"01234","address":"서울","addressDetail":"101호"}
                                """);
                mockMvc.perform(put("/api/products/{id}", id).header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON).content("""
                                                {"category":"DISHWASHER","name":null,"model":"  ","purchaseDate":null,
                                                 "postcode":null,"address":null,"addressDetail":null}
                                                """))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                                .andExpect(jsonPath("$.category").value("DISHWASHER"))
                                .andExpect(jsonPath("$.name").isEmpty()).andExpect(jsonPath("$.model").isEmpty())
                                .andExpect(jsonPath("$.purchaseDate").isEmpty())
                                .andExpect(jsonPath("$.postcode").isEmpty())
                                .andExpect(jsonPath("$.address").isEmpty())
                                .andExpect(jsonPath("$.addressDetail").isEmpty());
                assertThat(productRepository.count()).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                        "{}", "{\"category\":\"AIR_CONDITIONER\"}", "{\"category\":\"INVALID\"}",
                        "{\"category\":\"WASHER\",\"purchaseDate\":\"2025-02-29\"}",
                        "{\"category\":\"WASHER\",\"purchaseDate\":\"2024-02-30\"}",
                        "{\"category\":\"WASHER\",\"purchaseDate\":\"0000-01-01\"}",
                        "{\"category\":\"WASHER\",\"purchaseDate\":\"9999-12-31\"}",
                        "{\"category\":\"WASHER\",\"purchaseDate\":\"20240101\"}",
                        "{\"category\":\"WASHER\",\"postcode\":\"1234\"}",
                        "{\"category\":\"WASHER\",\"postcode\":\"abcde\"}",
                        "{\"category\":\"WASHER\",\"addressDetail\":\"101호\",\"address\":\"  \"}"
        })
        void invalidProductsDoNotCreateOrModifyRows(String body) throws Exception {
                mockMvc.perform(post("/api/products").header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isBadRequest());
                assertThat(productRepository.count()).isZero();
                long id = createProduct("{\"category\":\"WASHER\"}");
                mockMvc.perform(put("/api/products/{id}", id).header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isBadRequest());
                assertThat(productRepository.findById(id).orElseThrow().response().category())
                                .isEqualTo(ProductCategory.WASHER);
        }

        @ParameterizedTest
        @ValueSource(strings = { "name", "model", "address", "addressDetail" })
        void productFieldLengthLimitsAreEnforced(String field) throws Exception {
                int length = switch (field) {
                        case "name" -> 40;
                        case "model" -> 80;
                        case "address" -> 160;
                        default -> 100;
                };
                String body = "{\"category\":\"WASHER\",\"" + field + "\":\"" + "가".repeat(length + 1) + "\"}";
                mockMvc.perform(post("/api/products").header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                                .andExpect(status().isBadRequest());
                assertThat(productRepository.count()).isZero();
        }

        @Test
        void productsArePrivateAndUnknownIdsHaveTheSameResponse() throws Exception {
                long id = createProduct("{\"category\":\"WASHER\"}");
                appUserRepository.save(new AppUser("other@example.com", "unused", "USER"));
                String token = jwtTokenProvider.createToken("other@example.com");
                mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + token))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
                for (long target : new long[] { id, Long.MAX_VALUE }) {
                        mockMvc.perform(put("/api/products/{id}", target).header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"DRYER\"}"))
                                        .andExpect(status().isNotFound())
                                        .andExpect(jsonPath("$.message").value("제품을 찾을 수 없습니다."));
                }
                assertThat(productRepository.findById(id).orElseThrow().response().category())
                                .isEqualTo(ProductCategory.WASHER);
        }

        @Test
        void deleteRemovesOnlySelectedProductAndUpdatesList() throws Exception {
                long removed = createProduct("{\"category\":\"WASHER\",\"name\":\"삭제할 제품\"}");
                long retained = createProduct("{\"category\":\"REFRIGERATOR\",\"name\":\"유지할 제품\"}");
                String ownerToken = accessToken;
                appUserRepository.save(new AppUser("other@example.com", "unused", "USER"));
                accessToken = jwtTokenProvider.createToken("other@example.com");
                long otherProduct = createProduct("{\"category\":\"DRYER\"}");
                accessToken = ownerToken;
                var user = appUserRepository.findByEmail("chat-test@example.com").orElseThrow();
                var conversation = conversationRepository.save(new Conversation(user, "기존 상담", ProductCategory.WASHER));
                messageRepository.save(new ChatMessage(conversation, "user", "기존 질문"));

                var result = mockMvc
                                .perform(delete("/api/products/{id}", removed).header("Authorization",
                                                "Bearer " + accessToken))
                                .andExpect(status().isNoContent()).andReturn();
                assertThat(result.getResponse().getContentAsString()).isEmpty();
                assertThat(productRepository.existsById(removed)).isFalse();
                assertThat(productRepository.existsById(retained)).isTrue();
                assertThat(productRepository.existsById(otherProduct)).isTrue();
                assertThat(productRepository.count()).isEqualTo(2);
                assertThat(conversationRepository.existsById(conversation.getId())).isTrue();
                assertThat(messageRepository.count()).isEqualTo(1);
                mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + accessToken))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                                .andExpect(jsonPath("$[0].id").value(retained))
                                .andExpect(jsonPath("$[0].name").value("유지할 제품"));
                mockMvc.perform(delete("/api/products/{id}", retained).header("Authorization", "Bearer " + accessToken))
                                .andExpect(status().isNoContent());
                mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + accessToken))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        void otherUsersProductsAndMissingProductsCannotBeDeleted() throws Exception {
                long id = createProduct("{\"category\":\"WASHER\"}");
                appUserRepository.save(new AppUser("other@example.com", "unused", "USER"));
                String token = jwtTokenProvider.createToken("other@example.com");
                for (long target : new long[] { id, Long.MAX_VALUE }) {
                        mockMvc.perform(delete("/api/products/{id}", target).header("Authorization", "Bearer " + token))
                                        .andExpect(status().isNotFound())
                                        .andExpect(jsonPath("$.message").value("제품을 찾을 수 없습니다."));
                }
                assertThat(productRepository.existsById(id)).isTrue();
                assertThat(productRepository.count()).isEqualTo(1);
        }

        @Test
        void missingAndAlreadyDeletedProductsReturnNotFound() throws Exception {
                long id = createProduct("{\"category\":\"WASHER\"}");
                mockMvc.perform(delete("/api/products/{id}", id).header("Authorization", "Bearer " + accessToken))
                                .andExpect(status().isNoContent());
                for (long target : new long[] { id, Long.MAX_VALUE }) {
                        mockMvc.perform(delete("/api/products/{id}", target).header("Authorization",
                                        "Bearer " + accessToken))
                                        .andExpect(status().isNotFound())
                                        .andExpect(jsonPath("$.message").value("제품을 찾을 수 없습니다."));
                }
                assertThat(productRepository.count()).isZero();
        }

        @Test
        void productsRequireValidAuthentication() throws Exception {
                long id = createProduct("{\"category\":\"WASHER\"}");
                Instant now = Instant.now();
                var claims = JwtClaimsSet.builder().subject("chat-test@example.com")
                                .issuedAt(now.minusSeconds(600)).expiresAt(now.minusSeconds(300)).build();
                String expired = jwtEncoder.encode(JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
                mockMvc.perform(delete("/api/products/{id}", id)).andExpect(status().isUnauthorized());
                for (String token : new String[] { "", "invalid", expired }) {
                        mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + token))
                                        .andExpect(status().isUnauthorized());
                        mockMvc.perform(post("/api/products").header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"WASHER\"}"))
                                        .andExpect(status().isUnauthorized());
                        mockMvc.perform(put("/api/products/{id}", id).header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"DRYER\"}"))
                                        .andExpect(status().isUnauthorized());
                        mockMvc.perform(delete("/api/products/{id}", id).header("Authorization", "Bearer " + token))
                                        .andExpect(status().isUnauthorized());
                }
                assertThat(productRepository.count()).isEqualTo(1);
        }

        @Test
        void airConditionerRequestsAreRejectedBeforeAiOrPersistence() throws Exception {
                mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"질문\",\"category\":\"AIR_CONDITIONER\"}"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("지원 종료")));
                assertThat(conversationRepository.count()).isZero();
                assertThat(messageRepository.count()).isZero();
                assertThat(requestBody.get()).isNull();
                assertThat(embeddingRequest.get()).isNull();
        }

        @Test
        void legacyAirConditionerHistoryAndEvidenceRemainReadableAndDeletable() throws Exception {
                var user = appUserRepository.findByEmail("chat-test@example.com").orElseThrow();
                var conversation = conversationRepository
                                .save(new Conversation(user, "과거 에어컨 상담", ProductCategory.AIR_CONDITIONER));
                var reply = new ChatMessage(conversation, "assistant", "과거 답변");
                reply.setSources(List.of(new RagSource("HC-AC-003", "과거 근거", ProductCategory.AIR_CONDITIONER,
                                "1.0", "rag-test-data/manuals/air-conditioner.md", "보존할 근거 본문")));
                messageRepository.saveAll(List.of(new ChatMessage(conversation, "user", "과거 질문"), reply));
                long id = conversation.getId();
                var previousUpdate = conversationRepository.findById(id).orElseThrow().getUpdatedAt();
                for (String category : new String[] { "", ",\"category\":\"WASHER\"",
                                ",\"category\":\"AIR_CONDITIONER\"" }) {
                        mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"message\":\"추가 질문\",\"conversationId\":" + id + category + "}"))
                                        .andExpect(status().isBadRequest());
                }
                assertThat(messageRepository.count()).isEqualTo(2);
                assertThat(conversationRepository.findById(id).orElseThrow().getUpdatedAt()).isEqualTo(previousUpdate);
                assertThat(requestBody.get()).isNull();
                assertThat(embeddingRequest.get()).isNull();
                mockMvc.perform(get("/api/conversations").header("Authorization", "Bearer " + accessToken))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].category").value("AIR_CONDITIONER"));
                mockMvc.perform(get("/api/conversations/{id}", id).header("Authorization", "Bearer " + accessToken))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.category").value("AIR_CONDITIONER"))
                                .andExpect(jsonPath("$.messages[1].content").value("과거 답변"))
                                .andExpect(jsonPath("$.messages[1].sources[0].content").value("보존할 근거 본문"));
                mockMvc.perform(delete("/api/conversations/{id}", id).header("Authorization", "Bearer " + accessToken))
                                .andExpect(status().isNoContent());
                assertThat(messageRepository.count()).isZero();
        }

        @Test
        void airConditionerMentionReturnsRetirementNoticeWithoutAi() throws Exception {
                mockMvc.perform(post("/api/chat").header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"에어컨이 안 켜져요\",\"category\":\"WASHER\"}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("지원 종료")))
                                .andExpect(jsonPath("$.sources.length()").value(0));
                assertThat(requestBody.get()).isNull();
                assertThat(embeddingRequest.get()).isNull();
        }

        @Test
        void activeCatalogContainsOnlySixteenSupportedDocuments() {
                assertThat(manualCatalog.documents()).hasSize(16).allMatch(
                                document -> !document.getMetadata().get("category").equals("AIR_CONDITIONER"));
        }
    @Test
    void addressSearchRegionIsStoredAndATypedReplacementAddressClearsIt() throws Exception {
        long id = createProduct("""
                {"category":"REFRIGERATOR","model":"S836MEE022","purchaseDate":"2026-05-05","postcode":"63122",
                "address":"제주특별자치도 제주시 첨단로 1","deliverySido":"제주특별자치도","deliverySigungu":"제주시"}
                """);
        mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + accessToken))
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].deliverySido").value("제주"))
                .andExpect(jsonPath("$[0].deliverySigungu").value("제주시"));
        // An unchanged address keeps the searched region; a typed replacement address must not inherit it.
        mockMvc.perform(put("/api/products/{id}", id).header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"category":"REFRIGERATOR","name":"주방 냉장고","model":"S836MEE022","purchaseDate":"2026-05-05",
                        "postcode":"63122","address":"제주특별자치도 제주시 첨단로 1"}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deliverySido").value("제주"));
        mockMvc.perform(put("/api/products/{id}", id).header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"category":"REFRIGERATOR","model":"S836MEE022","purchaseDate":"2026-05-05","address":"서울시 강남구"}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deliverySido").value("")).andExpect(jsonPath("$.deliverySigungu").value(""));
        for (String invalid : List.of("{\"category\":\"WASHER\",\"deliverySido\":\"서울\"}",
                "{\"category\":\"WASHER\",\"address\":\"서울시 강남구\",\"deliverySido\":\"화성\"}",
                "{\"category\":\"WASHER\",\"address\":\"서울시 강남구\",\"deliverySigungu\":\"강남구\"}")) {
            mockMvc.perform(post("/api/products").header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void profileApiUpdatesTheSameProductUsedByMcpWithoutLosingDeliveryRegion() throws Exception {
        var created = mockMvc.perform(post("/api/me/products").header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"displayName":"우리 세탁기","warranty":{"category":"WASHER","modelName":"F21VDSK",
                        "purchaseDate":"2023-01-01","coreVariant":"STANDARD_MOTOR","productType":"GENERAL",
                        "proofAvailable":true,"domesticNewHousehold":true},"deliverySido":"서울","deliverySigungu":"강남구"}
                        """))
                .andExpect(status().isCreated()).andReturn();
        long id = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(get("/api/products").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].model").value("F21VDSK"));
        mockMvc.perform(put("/api/products/{id}", id).header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"category":"WASHER","name":"수정한 세탁기","model":"F24VDSK","purchaseDate":"2024-01-01",
                        "postcode":"01234","address":"서울시 강남구"}
                        """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/me/products").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].displayName").value("수정한 세탁기"))
                .andExpect(jsonPath("$[0].warranty.modelName").value("F24VDSK"))
                .andExpect(jsonPath("$[0].warranty.purchaseDate").value("2024-01-01"))
                .andExpect(jsonPath("$[0].warranty.proofAvailable").isEmpty())
                .andExpect(jsonPath("$[0].deliverySido").value("서울"))
                .andExpect(jsonPath("$[0].deliverySigungu").value("강남구"));
        assertThat(productRepository.count()).isEqualTo(1);
    }

    @Test
    void deletingSelectedProductPreservesHistoryAndSafetyButResetsDiagnostics() throws Exception {
        long id = createProduct("{\"category\":\"WASHER\"}");
        var state = consultations.begin("chat-test@example.com", null, ProductCategory.WASHER, "가스 냄새가 나요", id);
        var conversation = conversationRepository.findById(state.id()).orElseThrow();
        conversation.setConsultation(id, conversation.getLatestInput(), """
                {"riskStatus":"CONFIRMED","riskCase":"RC11","riskQuestionNo":1,"errorCode":"OE",
                 "symptomCode":"DRAIN","doneSteps":["STEP1"],"currentStep":1,"closed":false}
                """);
        conversationRepository.save(conversation);
        messageRepository.save(new ChatMessage(conversation, "assistant", "안전 안내"));
        var before = consultations.snapshot("chat-test@example.com", state.id());
        mockMvc.perform(delete("/api/products/{id}", id).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        var after = consultations.snapshot("chat-test@example.com", state.id());
        assertThat(after.productId()).isNull();
        assertThat(after.version()).isGreaterThan(before.version());
        assertThat(after.state().riskStatus()).isEqualTo("CONFIRMED");
        assertThat(after.state().doneSteps()).isEmpty();
        assertThat(after.state().symptomCode()).isNull();
        assertThat(messageRepository.findByConversationIdOrderByIdAsc(state.id())).hasSize(1);
    }

    @Test
    void withdrawalDeletesOnlyOwnersMcpAuditRecords() throws Exception {
        var owner = appUserRepository.findByEmail("chat-test@example.com").orElseThrow();
        var other = appUserRepository.save(new AppUser("other@example.com", "unused", "USER"));
        var ownedConversation = conversationRepository.save(new Conversation(owner, "내 상담", ProductCategory.WASHER));
        var otherConversation = conversationRepository.save(new Conversation(other, "다른 상담", ProductCategory.WASHER));
        var call = com.project.backend.mcp.audit.ToolCallRecord.failure("check_warranty", java.util.Map.of(), "MODEL", "UNAVAILABLE", 1);
        toolCallRepository.save(new com.project.backend.mcp.audit.ToolCallLog(ownedConversation.getId(), null, 1, call, "{}", "{}"));
        toolCallRepository.save(new com.project.backend.mcp.audit.ToolCallLog(otherConversation.getId(), null, 1, call, "{}", "{}"));
        mockMvc.perform(delete("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());
        assertThat(toolCallRepository.findByConversationIdOrderByIdAsc(ownedConversation.getId())).isEmpty();
        assertThat(toolCallRepository.findByConversationIdOrderByIdAsc(otherConversation.getId())).hasSize(1);
        assertThat(appUserRepository.existsById(other.getId())).isTrue();
    }
}
