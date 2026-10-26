package com.talentai.cvinbox;

import com.talentai.admin.service.LetterFingerprint;
import com.talentai.cvinbox.config.SftpServerConfig;
import com.talentai.cvinbox.service.CvIdentityExtractor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CvIdentityExtractorTest {

    @Test
    void emailIsLowerCased() {
        assertThat(CvIdentityExtractor.extractEmail("Jordan Lee\nJordan.Lee@Example.COM\nEngineer")).isEqualTo("jordan.lee@example.com");
    }

    @Test
    void emailInTheHeaderBeatsOneFurtherDown() {
        String cv = "Jordan Lee\njordan@home.com\nEngineer\n" + "filler line\n".repeat(30) + "old employer: hr@acme.com";
        assertThat(CvIdentityExtractor.extractEmail(cv)).isEqualTo("jordan@home.com");
    }

    @Test
    void aRefereesEmailOnTheSameLineIsSkipped() {
        String cv = "Jordan Lee\nReferee: boss@acme.com\nContact: jordan@home.com";
        assertThat(CvIdentityExtractor.extractEmail(cv)).isEqualTo("jordan@home.com");
    }

    @Test
    void aRefereesEmailOnTheNextLineIsSkipped() {
        String cv = "Jordan Lee\nReferences\nboss@acme.com\nEmail jordan@home.com";
        assertThat(CvIdentityExtractor.extractEmail(cv)).isEqualTo("jordan@home.com");
    }

    @Test
    void noTrustworthyEmailGivesNull() {
        assertThat(CvIdentityExtractor.extractEmail("Jordan Lee\nReferee: boss@acme.com")).isNull();
        assertThat(CvIdentityExtractor.extractEmail("no address here")).isNull();
        assertThat(CvIdentityExtractor.extractEmail(null)).isNull();
    }

    @Test
    void headingsAreNotTakenAsNames() {
        assertThat(CvIdentityExtractor.extractName("Curriculum Vitae\nPriya Nair\npriya@x.com")).isEqualTo("Priya Nair");
        assertThat(CvIdentityExtractor.extractName("RESUME\n\nHafizuddin bin razak")).isEqualTo("Hafizuddin Bin Razak");
        assertThat(CvIdentityExtractor.extractName("Curriculum Vitae\n12345\n")).isNull();
    }

    @Test
    void sameNameIgnoresCaseOrderAndPunctuation() {
        assertThat(LetterFingerprint.sameName("Hafizuddin Bin Razak", "razak bin  HAFIZUDDIN")).isTrue();
        assertThat(LetterFingerprint.sameName("Real Jordan", "Priya Nair")).isFalse();
        assertThat(LetterFingerprint.sameName("Hafizuddin Bin Razak", "Hafizuddin Razak")).isFalse();
    }

    @Test
    void theDefaultAndShortSftpPasswordsAreRefused() {
        assertThat(SftpServerConfig.isWeakPassword("change-me")).isTrue();
        assertThat(SftpServerConfig.isWeakPassword("CHANGE-ME")).isTrue();
        assertThat(SftpServerConfig.isWeakPassword("short")).isTrue();
        assertThat(SftpServerConfig.isWeakPassword("")).isTrue();
        assertThat(SftpServerConfig.isWeakPassword(null)).isTrue();
        assertThat(SftpServerConfig.isWeakPassword("a-long-enough-one")).isFalse();
    }
}
