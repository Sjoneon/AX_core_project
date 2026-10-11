package com.project.backend.auth.dto;

public record LoginResponse(String accessToken, String email) {

    @Override
    public String toString() {
        return "LoginResponse[email=" + email + "]";
    }
}
