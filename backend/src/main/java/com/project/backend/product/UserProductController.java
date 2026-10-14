package com.project.backend.product;
import java.security.Principal;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
@RestController @RequestMapping("/api/me/products")
public class UserProductController {
    private final UserProductService service;
    public UserProductController(UserProductService service){this.service=service;}
    @GetMapping public List<UserProductService.View> list(Principal principal){return service.list(principal.getName());}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public UserProductService.View create(@Valid @RequestBody UserProductService.Create request,Principal principal){return service.create(principal.getName(),request);}
}
