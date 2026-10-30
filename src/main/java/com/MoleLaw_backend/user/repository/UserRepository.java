package com.MoleLaw_backend.user.repository;

import com.MoleLaw_backend.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email and u.provider = :provider")
    Optional<User> findByCredentialsForUpdate(@Param("email") String email, @Param("provider") String provider);
    boolean existsByEmailAndProvider(String email, String provider);
    Optional<User> findByProviderAndProviderSubject(String provider, String providerSubject);
    Optional<User> findByEmailAndProvider(String email, String provider);
}
