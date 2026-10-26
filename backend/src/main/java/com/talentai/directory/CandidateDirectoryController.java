package com.talentai.directory;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/candidates")
@RequiredArgsConstructor
public class CandidateDirectoryController {

    private final CandidateDirectoryService directoryService;

    /** Global search by email, name, phone, skills or headline. Fewer than two characters returns nothing. */
    @GetMapping("/search")
    public ResponseEntity<List<CandidateDirectoryService.SearchHit>> search(@RequestParam("q") String q,
                                                                           Authentication auth) {
        return ResponseEntity.ok(directoryService.search(q, auth.getName()));
    }

    /** Everything held about one candidate. The record of who viewed it is shown to admins only. */
    @GetMapping("/{id}/profile")
    public ResponseEntity<CandidateDirectoryService.Profile> profile(@PathVariable Long id, Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return ResponseEntity.ok(directoryService.profile(id, auth.getName(), admin));
    }
}
