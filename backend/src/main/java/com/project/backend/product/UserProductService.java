package com.project.backend.product;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.project.backend.user.AppUserRepository;
import com.project.backend.warranty.*;

@Service
public class UserProductService {
    public record Create(@Size(max=100) String displayName, @NotNull @Valid WarrantyModels.Request warranty,
                         @NotBlank @Size(max=20) String deliverySido, @NotBlank @Size(max=30) String deliverySigungu) { }
    public record View(Long id,String displayName,WarrantyModels.Request warranty,String deliverySido,String deliverySigungu,long revision) { }
    private final UserProductRepository products;
    private final AppUserRepository users;
    private final WarrantyService warranty;
    public UserProductService(UserProductRepository products,AppUserRepository users,WarrantyService warranty) {
        this.products=products;this.users=users;this.warranty=warranty;
    }
    @Transactional(readOnly=true) public List<View> list(String email){return products.findByUserEmailOrderByIdDesc(email).stream().map(this::view).toList();}
    @Transactional public View create(String email,Create request) {
        if(request.warranty().purchaseDate()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"구매일은 필수입니다.");
        warranty.check(request.warranty());
        String sido=normalizeSido(request.deliverySido());
        if(!request.deliverySigungu().matches("[가-힣A-Za-z0-9 ·.-]{1,30}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"시·군·구를 확인해 주세요.");
        var owner=users.findByEmail(email).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return view(products.save(new UserProduct(owner,request.displayName(),request.warranty(),sido,request.deliverySigungu().strip())));
    }
    @Transactional(readOnly=true) public UserProduct owned(String email,Long id){return products.findByIdAndUserEmail(id,email).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"제품을 찾을 수 없습니다."));}
    public View view(UserProduct p){return new View(p.getId(),p.getDisplayName(),p.warranty(),p.getDeliverySido(),p.getDeliverySigungu(),p.getRevision());}
    public static String normalizeSido(String input) {
        String value=input.strip().replace("통합특별시", "").replace("특별자치도", "").replace("특별자치시", "").replace("특별시", "").replace("광역시", "");
        value=switch(value){case "경기도"->"경기";case "강원도"->"강원";case "충청북도"->"충북";case "충청남도"->"충남";case "전라북도"->"전북";case "전라남도"->"전남";case "경상북도"->"경북";case "경상남도"->"경남";case "제주도"->"제주";default->value;};
        if(!List.of("서울","부산","대구","인천","광주","대전","울산","세종","경기","강원","충북","충남","전북","전남","경북","경남","제주","전남광주").contains(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"시·도를 확인해 주세요.");
        return value;
    }
}
