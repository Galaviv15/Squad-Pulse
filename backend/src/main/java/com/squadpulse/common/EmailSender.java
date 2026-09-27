package com.squadpulse.common;

/**
 * Sends a plain-text email. The one way any module sends mail, so the provider can be swapped
 * without touching callers (see docs/spec.md section 10). No real provider is chosen yet: {@link
 * LoggingEmailSender} is the only implementation until deployment (Phase 6+).
 *
 * <p><b>A real implementation must send asynchronously</b> — queue the message and return at once.
 * Callers such as {@code POST /auth/forgot-password} send only for a registered email and answer
 * identically otherwise; a synchronous call to a remote provider would add its latency to exactly
 * those requests, and the response time would reveal which emails are registered.
 */
public interface EmailSender {

  /**
   * @param to the recipient's address
   * @param subject the subject line
   * @param body the plain-text body; may carry a one-time secret (e.g. an activation code), so an
   *     implementation must not persist or forward it anywhere but the recipient
   */
  void send(String to, String subject, String body);
}
