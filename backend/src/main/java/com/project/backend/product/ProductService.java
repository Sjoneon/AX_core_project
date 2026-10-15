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
import com.project.backend.conversation.ConversationRepository;
import com.project.backend.mcp.consultation.ConsultationService;
import com.project.backend.mcp.consultation.ConsultationState;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProductService {
    private final UserProductRepository productRepository;
    private final AppUserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public ProductService(UserProductRepository productRepository, AppUserRepository userRepository,
            ConversationRepository conversationRepository) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
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
        String sido = sido(request);
        product.update(request, purchaseDate, sido, sido == null ? null : sigungu(request));
        return productRepository.save(product).response();
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request, String email) {
        var product = productRepository.findByIdAndUserEmail(id, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "제품을 찾을 수 없습니다."));
        var purchaseDate = validate(request);
        boolean sameCategory = product.getCategory() == request.category();
        String sido = sido(request);
        product.update(request, purchaseDate, sido, sido == null ? null : sigungu(request));
        refreshConsultations(id, email, sameCategory ? id : null);
        return product.response();
    }

    @Transactional
    public void delete(Long id, String email) {
        var product = productRepository.findByIdAndUserEmail(id, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "제품을 찾을 수 없습니다."));
        refreshConsultations(id, email, null);
        productRepository.delete(product);
    }

    private void refreshConsultations(Long id, String email, Long selectedProductId) {
        for (var conversation : conversationRepository.findByProductIdAndUserEmail(id, email)) {
            var old = ConsultationService.state(conversation);
            var state = new ConsultationState(old.riskStatus(), old.riskCase(), old.riskQuestionNo(),
                    old.errorCode(), null, List.of(), 0, old.closed());
            conversation.setConsultation(selectedProductId, conversation.getLatestInput(), JSON.writeValueAsString(state));
        }
    }

    /** Normalized region from the address search, or null when the request carries none. */
    private static String sido(ProductRequest request) {
        if (request.deliverySido() == null) {
            if (request.deliverySigungu() != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시·도를 확인해 주세요.");
            return null;
        }
        if (request.address() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "설치 지역은 주소 검색으로 입력해 주세요.");
        return UserProductService.normalizeSido(request.deliverySido());
    }

    // Sejong has no 시·군·구 in address search results, so an empty value is kept as empty.
    private static String sigungu(ProductRequest request) {
        String value = request.deliverySigungu();
        if (value == null) return "";
        if (!value.matches("[가-힣A-Za-z0-9 ·.-]{1,30}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시·군·구를 확인해 주세요.");
        return value;
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
