package com.project.backend.chat.exception;

public class ChatUnavailableException extends RuntimeException {

    public ChatUnavailableException(Throwable cause) {
        super("AI 응답을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.", cause);
    }
}
