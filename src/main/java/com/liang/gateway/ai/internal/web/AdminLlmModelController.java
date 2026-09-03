package com.liang.gateway.ai.internal.web;

import com.liang.gateway.ai.internal.application.LlmModelAdminService;
import com.liang.gateway.ai.internal.application.ModelSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/admin/llm/models")
public class AdminLlmModelController {

    private final LlmModelAdminService modelAdminService;

    public AdminLlmModelController(LlmModelAdminService modelAdminService) {
        this.modelAdminService = modelAdminService;
    }

    @PostMapping
    public Mono<ModelSnapshot> create(@Valid @RequestBody CreateModelRequest request) {
        return modelAdminService.create(
                request.name(),
                request.provider(),
                request.apikeyCode(),
                request.inputPriceFenPerMillion(),
                request.outputPriceFenPerMillion(),
                request.enabledOrDefault());
    }

    @GetMapping
    public Mono<List<ModelSnapshot>> list() {
        return modelAdminService.list();
    }

    @GetMapping("/{code}")
    public Mono<ModelSnapshot> get(@PathVariable String code) {
        return modelAdminService.get(code);
    }

    @PutMapping("/{code}")
    public Mono<ModelSnapshot> update(@PathVariable String code, @Valid @RequestBody UpdateModelRequest request) {
        return modelAdminService.update(
                code,
                request.name(),
                request.provider(),
                request.apikeyCode(),
                request.inputPriceFenPerMillion(),
                request.outputPriceFenPerMillion(),
                request.enabled());
    }

    public record CreateModelRequest(
            @NotBlank String name,
            @NotBlank String provider,
            @NotBlank String apikeyCode,
            @NotNull Long inputPriceFenPerMillion,
            @NotNull Long outputPriceFenPerMillion,
            Boolean enabled) {
        boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }

    public record UpdateModelRequest(
            String name,
            String provider,
            String apikeyCode,
            Long inputPriceFenPerMillion,
            Long outputPriceFenPerMillion,
            Boolean enabled) {}
}
