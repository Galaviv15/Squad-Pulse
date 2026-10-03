package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.common.archunitfixture.ClassUsingGridFs;
import com.squadpulse.common.archunitfixture.ClassUsingImageStorage;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * {@link GridFsConfinementTest} only proves the real codebase passes — true even if the rule were
 * broken. This evaluates the same {@link GridFsRules} rule against a violating fixture, and against
 * {@link GridFsImageStorage} plus a compliant fixture outside {@code common}, which are allowed.
 */
class GridFsConfinementRuleTest {

  @Test
  void failsForAClassOutsideCommonUsingGridFs() {
    EvaluationResult result =
        GridFsRules.ONLY_COMMON_MAY_USE_GRIDFS.evaluate(
            new ClassFileImporter().importClasses(ClassUsingGridFs.class));

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("GridFsOperations")
        .contains("GridFsResource")
        .contains("GridFSFile")
        .contains("use common.ImageStorage instead");
  }

  /** GridFS inside {@code common}, and only {@link ImageStorage} outside it. */
  @Test
  void allowsGridFsInsideCommonAndImageStorageOutsideIt() {
    assertThat(
            GridFsRules.ONLY_COMMON_MAY_USE_GRIDFS
                .evaluate(
                    new ClassFileImporter()
                        .importClasses(GridFsImageStorage.class, ClassUsingImageStorage.class))
                .hasViolation())
        .isFalse();
  }
}
