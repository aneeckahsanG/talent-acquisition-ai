package com.talentai.sourcing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "resume_source")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResumeSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name; // e.g. "JobStreet Malaysia"

    @Column(nullable = false)
    private String url;  // e.g. "http://localhost:8080/mock-jobboard/jobstreet/candidates"

    @Column(name = "source_type")
    private String sourceType; // LINKEDIN, JOBSTREET, CUSTOM

    @Builder.Default
    private Boolean active = true;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "last_sync_count")
    @Builder.Default
    private Integer lastSyncCount = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
