package com.novel.system.controller;

import com.novel.system.security.RequestAccessContextHolder;
import com.novel.system.service.AccessIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/access")
@RequiredArgsConstructor
public class AccessController {

    private final AccessIdentityService accessIdentityService;

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentAccessContext() {
        return ResponseEntity.ok(accessIdentityService.currentIdentity(RequestAccessContextHolder.current()));
    }
}
