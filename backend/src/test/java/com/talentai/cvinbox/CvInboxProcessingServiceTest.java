package com.talentai.cvinbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.common.entity.Candidate;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.common.util.PdfTextExtractor;
import com.talentai.cvinbox.dto.CvInboxDtos.ProcessNowResponse;
import com.talentai.cvinbox.entity.CvInboxItem;
import com.talentai.cvinbox.entity.CvInboxSettings;
import com.talentai.cvinbox.repository.CvInboxItemRepository;
import com.talentai.cvinbox.repository.CvInboxSettingsRepository;
import com.talentai.cvinbox.service.CvInboxProcessingService;
import com.talentai.cvinbox.service.ExternalSftpClientService;
import com.talentai.cvinbox.service.GoogleDriveClientService;
import com.talentai.cvinbox.service.GoogleDriveClientService.DriveCv;
import com.talentai.cvinbox.service.SharePointClientService;
import com.talentai.cvinbox.source.CvSourceFactory;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.screening.service.ScreeningAgentService.ParsedNameEmail;
import com.talentai.sourcing.service.SourcingAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Processing a CV whose email is already shared by several candidate records must not fail. */
class CvInboxProcessingServiceTest {

    private static final String SHARED_EMAIL = "shared@example.com";

    private final CandidateRepository candidates = mock(CandidateRepository.class);
    private final JobRequisitionRepository requisitions = mock(JobRequisitionRepository.class);
    private final CvInboxItemRepository items = mock(CvInboxItemRepository.class);
    private final CvInboxSettingsRepository settingsRepo = mock(CvInboxSettingsRepository.class);
    private final GoogleDriveClientService drive = mock(GoogleDriveClientService.class);
    private final PdfTextExtractor extractor = mock(PdfTextExtractor.class);
    private final ScreeningAgentService screening = mock(ScreeningAgentService.class);

    private CvInboxProcessingService service;

    @BeforeEach
    void setUp() throws Exception {
        CvSourceFactory sources = new CvSourceFactory(mock(ExternalSftpClientService.class), drive, mock(SharePointClientService.class), "./unused");
        service = new CvInboxProcessingService(candidates, requisitions, mock(PipelineStageRepository.class),
                mock(SourcingAgentService.class), screening, items, settingsRepo, sources, extractor, new ObjectMapper());

        CvInboxSettings settings = CvInboxSettings.builder().id(1L).mode("GOOGLE_DRIVE")
                .googleFolderId("folder-1").googleServiceAccountJson("{}").build();
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(settings));
        when(drive.listAndFetch("{}", "folder-1")).thenReturn(List.of(
                DriveCv.builder().fileId("file-1").fileName("Arjun.pdf").content(new byte[]{1}).build()));
        when(extractor.extractText(anyString(), any())).thenReturn("Arjun Pillai resume text");
        when(screening.extractNameAndEmail(anyString())).thenReturn(new ParsedNameEmail("Arjun Pillai", SHARED_EMAIL));
        when(requisitions.findByStatus("OPEN")).thenReturn(List.of()); // no open roles, so no AI scoring in this test
        when(candidates.save(any(Candidate.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void demoModeKeepsPeopleWhoShareAnEmailSeparate() {
        ReflectionTestUtils.setField(service, "demoMode", true);

        ProcessNowResponse result = service.processNow();

        assertEquals(1, result.getFilesFound());
        assertEquals(0, result.getFailed(), "a shared email must not fail the file");
        assertEquals(1, result.getExcluded());
        verify(candidates, never()).findFirstByEmailOrderByIdAsc(anyString());

        ArgumentCaptor<Candidate> saved = ArgumentCaptor.forClass(Candidate.class);
        verify(candidates).save(saved.capture());
        assertNull(saved.getValue().getId(), "a new record, not an overwrite of an existing person");
        assertEquals("Arjun Pillai", saved.getValue().getFullName());
        verify(drive).moveFile("{}", "folder-1", "file-1", false);
    }

    @Test
    void outsideDemoModeTheEarliestRecordWithThatEmailIsReused() {
        ReflectionTestUtils.setField(service, "demoMode", false);
        Candidate earliest = Candidate.builder().id(1L).fullName("Old Name").email(SHARED_EMAIL).build();
        when(candidates.findFirstByEmailOrderByIdAsc(SHARED_EMAIL)).thenReturn(Optional.of(earliest));

        ProcessNowResponse result = service.processNow();

        assertEquals(0, result.getFailed());
        ArgumentCaptor<Candidate> saved = ArgumentCaptor.forClass(Candidate.class);
        verify(candidates).save(saved.capture());
        assertEquals(1L, saved.getValue().getId());
        assertEquals("Arjun Pillai", saved.getValue().getFullName(), "identity refreshed from the new CV");
    }

    @Test
    void aFileThatCouldNotBeReadIsRecordedAsFailedAndStillFiledAway() {
        java.util.List<String> filedAway = new java.util.ArrayList<>();
        CvSourceFactory stub = mock(CvSourceFactory.class);
        when(stub.forSettings(any())).thenReturn(new com.talentai.cvinbox.source.CvSource() {
            public String label() { return "stub"; }
            public java.util.List<com.talentai.cvinbox.source.InboxFile> fetchAll() {
                return java.util.List.of(new com.talentai.cvinbox.source.InboxFile("ref-1", "locked.pdf", null));
            }
            public void fileAway(com.talentai.cvinbox.source.InboxFile f, boolean considered) { filedAway.add(f.ref() + ":" + considered); }
        });
        CvInboxProcessingService svc = new CvInboxProcessingService(candidates, requisitions, mock(PipelineStageRepository.class),
                mock(SourcingAgentService.class), screening, items, settingsRepo, stub, extractor, new ObjectMapper());

        ProcessNowResponse result = svc.processNow();

        assertEquals(1, result.getFailed());
        assertEquals(List.of("ref-1:false"), filedAway);
        ArgumentCaptor<CvInboxItem> item = ArgumentCaptor.forClass(CvInboxItem.class);
        verify(items).save(item.capture());
        assertEquals("FAILED", item.getValue().getStatus());
        assertEquals("locked.pdf", item.getValue().getFileName());
    }

    @Test
    void anInboxThatCannotBeListedProcessesNothingInsteadOfFailing() {
        CvSourceFactory stub = mock(CvSourceFactory.class);
        when(stub.forSettings(any())).thenReturn(new com.talentai.cvinbox.source.CvSource() {
            public String label() { return "stub"; }
            public java.util.List<com.talentai.cvinbox.source.InboxFile> fetchAll() throws Exception { throw new java.io.IOException("unreachable"); }
            public void fileAway(com.talentai.cvinbox.source.InboxFile f, boolean considered) { fail("nothing to file away"); }
        });
        CvInboxProcessingService svc = new CvInboxProcessingService(candidates, requisitions, mock(PipelineStageRepository.class),
                mock(SourcingAgentService.class), screening, items, settingsRepo, stub, extractor, new ObjectMapper());

        ProcessNowResponse result = svc.processNow();

        assertEquals(0, result.getFilesFound());
        assertEquals(0, result.getFailed());
        verify(items, never()).save(any());
    }

    @Test
    void theOutcomeIsRecordedForTheRecruiter() {
        ReflectionTestUtils.setField(service, "demoMode", true);

        service.processNow();

        ArgumentCaptor<CvInboxItem> item = ArgumentCaptor.forClass(CvInboxItem.class);
        verify(items).save(item.capture());
        assertEquals("Arjun.pdf", item.getValue().getFileName());
        assertEquals("PROCESSED", item.getValue().getStatus());
        assertEquals("EXCLUDED", item.getValue().getDecision());
    }
}
