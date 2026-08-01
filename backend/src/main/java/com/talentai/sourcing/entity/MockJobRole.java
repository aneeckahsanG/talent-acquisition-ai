package com.talentai.sourcing.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "mock_job_role")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MockJobRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String department;

    @Builder.Default
    private String location = "Kuala Lumpur, Malaysia";

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String requirements;

    private String company;

    private Long requisitionId;

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Builder.Default
    private Boolean active = true;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
