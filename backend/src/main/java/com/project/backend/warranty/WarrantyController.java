package com.project.backend.warranty;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/warranty")
public class WarrantyController {
    private final WarrantyService service;

    public WarrantyController(WarrantyService service) { this.service = service; }

    @GetMapping("/policies")
    public WarrantyModels.Catalog policies() { return service.policies(); }

    @PostMapping("/check")
    public WarrantyModels.Result check(@Valid @RequestBody WarrantyModels.Request request) {
        return service.check(request);
    }
}
