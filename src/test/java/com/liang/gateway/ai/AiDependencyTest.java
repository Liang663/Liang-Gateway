package com.liang.gateway.ai;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.liang.gateway.ai", importOptions = ImportOption.DoNotIncludeTests.class)
class AiDependencyTest {

    @ArchTest
    static final ArchRule doesNotDependOnOtherDomains = noClasses()
            .that()
            .resideInAPackage("com.liang.gateway.ai..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.liang.gateway.access..", "com.liang.gateway.core..", "com.liang.gateway.orchestration..");

    @ArchTest
    static final ArchRule mcpDoesNotDependOnChatOrAccessApi = noClasses()
            .that()
            .resideInAPackage("com.liang.gateway.ai.internal.mcp..")
            .should()
            .dependOnClassesThat()
            .haveNameMatching(".*(ChatApi|ChatUpstream|ChatUsage|DefaultChatApi|AccessApi)$");

    @ArchTest
    static final ArchRule mcpDoesNotDependOnChatPackages = noClasses()
            .that()
            .resideInAPackage("com.liang.gateway.ai.internal.mcp..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.liang.gateway.ai.internal.application..", "com.liang.gateway.ai.internal.web..");
}
