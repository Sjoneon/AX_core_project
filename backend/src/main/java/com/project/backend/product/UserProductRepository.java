package com.project.backend.product;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProductRepository extends JpaRepository<UserProduct, Long> {
    List<UserProduct> findByUserEmailOrderByCreatedAtDescIdDesc(String email);
    Optional<UserProduct> findByIdAndUserEmail(Long id, String email);
    void deleteAllByUserId(Long userId);
}
