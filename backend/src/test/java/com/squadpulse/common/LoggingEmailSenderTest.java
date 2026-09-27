package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LoggingEmailSenderTest {

  @Test
  void logsTheWholeMessageInsteadOfSendingIt(CapturedOutput output) {
    new LoggingEmailSender().send("coach@example.com", "Your code", "Code: 123456");

    assertThat(output.getOut())
        .contains("coach@example.com")
        .contains("Your code")
        .contains("Code: 123456");
  }
}
