package com.squadpulse.common;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Fails the build if any production class outside {@code common} depends on GridFS. See {@link
 * GridFsRules} for why. Only scans main sources: the violating fixture in {@code archunitfixture}
 * isn't production code.
 */
@AnalyzeClasses(packages = "com.squadpulse", importOptions = ImportOption.DoNotIncludeTests.class)
class GridFsConfinementTest {

  @ArchTest static final ArchRule onlyCommonMayUseGridFs = GridFsRules.ONLY_COMMON_MAY_USE_GRIDFS;
}
