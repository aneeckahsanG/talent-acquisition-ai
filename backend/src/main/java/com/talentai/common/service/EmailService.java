package com.talentai.common.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    @Async
    public void sendHtml(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom("TalentAcquisition AI <" + fromAddress + ">");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Email sent to {} — subject: {}", to, subject);
        } catch (MessagingException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        }
    }

    @Async
    public void sendPlain(String to, String subject, String body) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom("TalentAcquisition AI <" + fromAddress + ">");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
            log.info("Email sent to {} — subject: {}", to, subject);
        } catch (MessagingException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        }
    }

    /**
     * Sends the single, consistent candidate rejection notification used across
     * the whole app (pipeline board, direct-applicant reject, screening reviewer
     * decisions) — regardless of which stage or flow triggered the rejection.
     */
    public void sendRejectionEmail(String toEmail, String candidateFullName, String requisitionTitle, boolean hadInterview) {
        String firstName = (candidateFullName == null || candidateFullName.isBlank())
                ? "there" : candidateFullName.trim().split("\\s+")[0];
        String intro = hadInterview
                ? "Thank you for your interest in the " + requisitionTitle
                        + " position, and for taking the time to interview with our team."
                : "Thank you for your interest in the " + requisitionTitle + " position.";
        String body = "Dear " + firstName + ",\n\n"
                + intro + " After careful consideration, we have decided to move forward with "
                + "other candidates at this time.\n\n"
                + "We appreciate the time you invested and encourage you to apply for future openings.\n\n"
                + "Best regards,\nHuman Resources\nTalentAcquisition AI";
        sendHtml(toEmail, "Application Update – " + requisitionTitle, wrapInTemplate(body, "TA"));
    }

    /** Wraps plain text invitation email in a clean HTML template. */
    public String wrapInTemplate(String bodyText, String logoInitials) {
        String escaped = bodyText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br/>");
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"/></head>
                <body style="margin:0;padding:0;background:#F1F5F9;font-family:Arial,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                    <tr><td align="center">
                      <table width="600" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08);">
                        <tr>
                          <td style="background:linear-gradient(135deg,#6366F1,#8B5CF6);padding:28px 36px;">
                            <table><tr>
                              <td style="width:44px;height:44px;background:rgba(255,255,255,0.2);border-radius:10px;text-align:center;vertical-align:middle;">
                                <span style="color:#fff;font-size:18px;font-weight:bold;">%s</span>
                              </td>
                              <td style="padding-left:12px;">
                                <div style="color:#fff;font-size:18px;font-weight:bold;">TalentAcquisition AI</div>
                                <div style="color:rgba(255,255,255,0.75);font-size:12px;">Agentic Talent Sourcing Platform</div>
                              </td>
                            </tr></table>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:36px;color:#334155;font-size:15px;line-height:1.7;">
                            %s
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:20px 36px;background:#F8FAFC;border-top:1px solid #E2E8F0;font-size:12px;color:#94A3B8;text-align:center;">
                            This email was sent by TalentAcquisition AI on behalf of the recruiting team.
                          </td>
                        </tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(logoInitials, escaped);
    }
}
