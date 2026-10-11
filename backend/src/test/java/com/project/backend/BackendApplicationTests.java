package com.project.backend;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
		useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
		properties = "spring.ai.openai.api-key=test-key")
@AutoConfigureMockMvc
class BackendApplicationTests {

	private static final AtomicInteger upstreamStatus = new AtomicInteger(200);
	private static final AtomicReference<String> upstreamBody = new AtomicReference<>();
	private static final AtomicReference<String> requestBody = new AtomicReference<>();
	private static final AtomicReference<String> authorization = new AtomicReference<>();
	private static HttpServer upstream;

	@Autowired
	private MockMvc mockMvc;

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
		upstream.start();
		registry.add("spring.ai.openai.base-url",
				() -> "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1");
	}

	@BeforeEach
	void resetUpstream() {
		upstreamStatus.set(200);
		upstreamBody.set(completion("안녕하세요!"));
		requestBody.set(null);
		authorization.set(null);
	}

	@AfterAll
	static void stopUpstream() {
		if (upstream != null) {
			upstream.stop(0);
		}
	}

	@Test
	void chatReturnsReplyFromSpringAi() throws Exception {
		mockMvc.perform(post("/api/chat")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"안녕하세요 {name}\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reply").value("안녕하세요!"));

		assertThat(requestBody.get()).contains("gpt-4o-mini", "안녕하세요 {name}", "\"system\"", "\"user\"");
		assertThat(authorization.get()).isEqualTo("Bearer test-key");
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"message\":null}", "{\"message\":\"\"}", "{\"message\":\"   \"}"})
	void invalidMessageDoesNotCallLlm(String body) throws Exception {
		mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("message는 필수입니다."));

		assertThat(requestBody.get()).isNull();
	}

	@Test
	void tooLongMessageDoesNotCallLlm() throws Exception {
		mockMvc.perform(post("/api/chat")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"" + "a".repeat(4001) + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("message는 4000자 이하여야 합니다."));

		assertThat(requestBody.get()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"{", ""})
	void invalidJsonDoesNotCallLlm(String body) throws Exception {
		mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("올바른 JSON 요청 본문이 필요합니다."));

		assertThat(requestBody.get()).isNull();
	}

	@Test
	void upstreamFailureReturnsSafeError() throws Exception {
		upstreamStatus.set(401);
		upstreamBody.set("{\"error\":{\"message\":\"sensitive upstream detail\",\"type\":\"invalid_request_error\"}}");

		mockMvc.perform(post("/api/chat")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"안녕\"}"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.message").value("AI 응답을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요."));
	}

	@Test
	void emptyReplyReturnsBadGateway() throws Exception {
		upstreamBody.set(completion(""));

		mockMvc.perform(post("/api/chat")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"안녕\"}"))
				.andExpect(status().isBadGateway());
	}

	@Test
	void healthDoesNotCallLlm() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));

		assertThat(requestBody.get()).isNull();
	}

	private static String completion(String reply) {
		return """
				{"id":"chatcmpl-test","object":"chat.completion","created":1,"model":"gpt-4o-mini",
				"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
				"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
				""".formatted(reply);
	}

}
