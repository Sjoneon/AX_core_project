package com.project.backend.product;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserProductRepository extends JpaRepository<UserProduct,Long> {
    List<UserProduct> findByUserEmailOrderByIdDesc(String email);
    List<UserProduct> findByUserEmailOrderByCreatedAtDescIdDesc(String email);
    Optional<UserProduct> findByIdAndUserEmail(Long id,String email);
    void deleteAllByUserId(Long userId);
}
