package com.collaborativeeditor.controller;

import com.collaborativeeditor.domain.user.User;
import com.collaborativeeditor.dto.user.UserDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @GetMapping("/me")
    public ResponseEntity<UserDto> getCurrentUser(@AuthenticationPrincipal User user) {
        UserDto dto = UserDto.fromEntity(user, true);
        return ResponseEntity.ok(dto);
    }
}

