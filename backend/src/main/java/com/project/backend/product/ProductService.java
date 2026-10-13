package com.project.backend.product;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.project.backend.user.AppUserRepository;

@Service
public class ProductService {
    private final UserProductRepository productRepository;
    private final AppUserRepository userRepository;

    public ProductService(UserProductRepository productRepository, AppUserRepository userRepository) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> list(String email) {
        return productRepository.findByUserEmailOrderByCreatedAtDescIdDesc(email).stream()
                .map(product -> product.response()).toList();
    }

    @Transactional
    public ProductResponse create(ProductRequest request, String email) {
        LocalDate purchaseDate = validate(request);
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."));
        var product = new UserProduct(user);
        product.update(request, purchaseDate);
        return productRepository.save(product).response();
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request, String email) {
        var product = productRepository.findByIdAndUserEmail(id, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "제품을 찾을 수 없습니다."));
        product.update(request, validate(request));
        return product.response();
    }

    @Transactional
    public void delete(Long id, String email) {
        var product = productRepository.findByIdAndUserEmail(id, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "제품을 찾을 수 없습니다."));
        productRepository.delete(product);
    }

    private LocalDate validate(ProductRequest request) {
        if (!request.category().isSupported()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "에어컨은 지원 종료된 품목입니다.");
        }
        if (request.addressDetail() != null && request.address() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상세 주소를 입력하려면 기본 주소가 필요합니다.");
        }
        if (request.purchaseDate() == null)
            return null;
        try {
            LocalDate date = LocalDate.parse(request.purchaseDate());
            if (date.getYear() < 1 || date.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul")))) {
                throw new DateTimeParseException("Invalid purchase date", request.purchaseDate(), 0);
            }
            return date;
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "구매일은 오늘 이하의 실제 날짜여야 합니다.");
        }
    }
}
