package com.talentai.directory;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One search or profile view, kept so access to candidate personal data can be accounted for. */
@Entity
@Table(name = "candidate_access_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandidateAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false, length = 20)
    private String action; // SEARCH or VIEW

    @Column(name = "candidate_id")
    private Long candidateId;

    @Column(name = "search_text", length = 200)
    private String searchText;

    @Column(name = "result_count")
    private Integer resultCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
