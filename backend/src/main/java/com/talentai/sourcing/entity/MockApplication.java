package com.talentai.sourcing.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "mock_application")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MockApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long mockJobRoleId;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    private String headline;

    @Column(length = 1000)
    private String skills;

    private BigDecimal yearsExperience;

    @Column(columnDefinition = "TEXT")
    private String resumeText;

    @Builder.Default
    private String sourceChannel = "JOBBOARD";

    @CreationTimestamp
    private LocalDateTime createdAt;
}
