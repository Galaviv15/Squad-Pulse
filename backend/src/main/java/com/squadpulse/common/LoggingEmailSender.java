package com.squadpulse.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Local-development stand-in for a real email provider: writes each message — recipient, subject
 * and body — to the application log at INFO instead of sending it, so a developer can read an
 * activation or reset code off the console.
 *
 * <p><b>Must never be active in production.</b> The body carries one-time codes in plaintext, and
 * this puts them in the log, where anyone who can read the logs can take over the account the code
 * was sent for. Replacing it with a real (asynchronous — see {@link EmailSender}) provider is a
 * Phase 6+ deployment task; until then it's the only {@link EmailSender} bean, deliberately not
 * gated by profile.
 */
@Component
public class LoggingEmailSender implements EmailSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

  @Override
  public void send(String to, String subject, String body) {
    log.info("Email (not sent — logging stub) to={} subject=\"{}\"\n{}", to, subject, body);
  }
}
