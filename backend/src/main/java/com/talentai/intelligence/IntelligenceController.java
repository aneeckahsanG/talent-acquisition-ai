package com.talentai.intelligence;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/intelligence")
@RequiredArgsConstructor
public class IntelligenceController {

    private final IntelligenceService intelligenceService;
    private final ScreeningQualityService screeningQualityService;

    /** How consistent screening is, and how often recruiters agree with the AI. */
    @GetMapping("/screening-quality")
    public ScreeningQualityService.Quality screeningQuality() {
        return screeningQualityService.overview();
    }

    /** Everything the Talent Intelligence page shows, computed from current data. */
    @GetMapping("/overview")
    public IntelligenceService.Overview overview() {
        return intelligenceService.overview();
    }
}
