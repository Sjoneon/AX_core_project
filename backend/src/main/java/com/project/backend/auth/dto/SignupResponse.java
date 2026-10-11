package com.project.backend.auth.dto;

import com.project.backend.user.AppUser;

public record SignupResponse(Long id, String email, String role) {

    public static SignupResponse from(AppUser user) {
        return new SignupResponse(user.getId(), user.getEmail(), user.getRole());
    }
}
