package com.MoleLaw_backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "user",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"email", "provider"}) // ✅ 복합 유니크 키로 설정
        }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id @GeneratedValue
    private Long id;

    private String email;

    private String password;

    private String nickname;

    private String provider; // "google", "kakao"
}
