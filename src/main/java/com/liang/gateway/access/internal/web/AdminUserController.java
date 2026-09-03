package com.liang.gateway.access.internal.web;

import com.liang.gateway.access.internal.application.AccessNotFoundException;
import com.liang.gateway.access.internal.application.UserAdminService;
import com.liang.gateway.access.internal.application.UserSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private final UserAdminService userAdminService;

    public AdminUserController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @PostMapping
    public Mono<UserSnapshot> create(@Valid @RequestBody CreateUserRequest request) {
        return userAdminService.create(request.name(), request.authority(), request.enabledOrDefault());
    }

    @GetMapping
    public Mono<List<UserSnapshot>> list() {
        return userAdminService.list();
    }

    @GetMapping("/{userCode}")
    public Mono<UserSnapshot> get(@PathVariable String userCode) {
        return userAdminService
                .get(userCode)
                .switchIfEmpty(Mono.error(new AccessNotFoundException("User not found")));
    }

    @PutMapping("/{userCode}")
    public Mono<UserSnapshot> update(@PathVariable String userCode, @Valid @RequestBody UpdateUserRequest request) {
        return userAdminService.update(userCode, request.name(), request.authority(), request.enabled());
    }

    public record CreateUserRequest(
            @NotBlank String name, @NotBlank String authority, Boolean enabled) {
        boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }

    public record UpdateUserRequest(@NotBlank String name, @NotBlank String authority, Boolean enabled) {}
}
