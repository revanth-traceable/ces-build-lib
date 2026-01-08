package com.cloudogu.ces.cesbuildlib

/**
 * Code quality analysis tools for Java/Maven projects.
 *
 * Integrates Checkstyle, PMD, and SpotBugs for comprehensive static code analysis.
 * Supports running analyses in Docker containers for consistent results.
 *
 * @see https://checkstyle.org/
 * @see https://pmd.github.io/
 * @see https://spotbugs.github.io/
 */
class CodeQuality implements Serializable {
    private script
    private Maven maven

    // Tool configurations
    private String checkstyleVersion = "10.12.5"
    private String pmdVersion = "6.55.0"
    private String spotbugsVersion = "4.8.3"

    // Config files (optional)
    private String checkstyleConfigFile = null
    private String pmdRulesetFile = null
    private String spotbugsExcludeFile = null

    // Thresholds for build status
    private int checkstyleWarningThreshold = 50
    private int checkstyleErrorThreshold = 10
    private int pmdWarningThreshold = 50
    private int pmdErrorThreshold = 10
    private int spotbugsWarningThreshold = 20
    private int spotbugsErrorThreshold = 5

    // Analysis results
    private CheckstyleResult checkstyleResult = null
    private PmdResult pmdResult = null
    private SpotbugsResult spotbugsResult = null

    CodeQuality(script, Maven maven) {
        this.script = script
        this.maven = maven
    }

    /**
     * Configure Checkstyle settings.
     *
     * @param version Checkstyle version
     * @param configFile Custom config file path
     * @param warningThreshold Number of warnings before marking build unstable
     * @param errorThreshold Number of errors before failing build
     */
    CodeQuality withCheckstyle(String version = null, String configFile = null,
                                int warningThreshold = 50, int errorThreshold = 10) {
        if (version) this.checkstyleVersion = version
        this.checkstyleConfigFile = configFile
        this.checkstyleWarningThreshold = warningThreshold
        this.checkstyleErrorThreshold = errorThreshold
        return this
    }

    /**
     * Configure PMD settings.
     *
     * @param version PMD version
     * @param rulesetFile Custom ruleset file path
     * @param warningThreshold Number of warnings before marking build unstable
     * @param errorThreshold Number of errors before failing build
     */
    CodeQuality withPmd(String version = null, String rulesetFile = null,
                        int warningThreshold = 50, int errorThreshold = 10) {
        if (version) this.pmdVersion = version
        this.pmdRulesetFile = rulesetFile
        this.pmdWarningThreshold = warningThreshold
        this.pmdErrorThreshold = errorThreshold
        return this
    }

    /**
     * Configure SpotBugs settings.
     *
     * @param version SpotBugs version
     * @param excludeFile Exclude filter file path
     * @param warningThreshold Number of warnings before marking build unstable
     * @param errorThreshold Number of errors before failing build
     */
    CodeQuality withSpotbugs(String version = null, String excludeFile = null,
                              int warningThreshold = 20, int errorThreshold = 5) {
        if (version) this.spotbugsVersion = version
        this.spotbugsExcludeFile = excludeFile
        this.spotbugsWarningThreshold = warningThreshold
        this.spotbugsErrorThreshold = errorThreshold
        return this
    }

    /**
     * Run Checkstyle analysis.
     *
     * @param additionalArgs Additional Maven arguments
     * @return CheckstyleResult object
     */
    CheckstyleResult runCheckstyle(String additionalArgs = "") {
        script.echo "Running Checkstyle analysis (version: ${checkstyleVersion})"

        StringBuilder cmd = new StringBuilder()
        cmd.append("checkstyle:checkstyle")
        cmd.append(" -Dcheckstyle.version=${checkstyleVersion}")

        if (checkstyleConfigFile && script.fileExists(checkstyleConfigFile)) {
            cmd.append(" -Dcheckstyle.config.location=${checkstyleConfigFile}")
        }

        if (additionalArgs) {
            cmd.append(" ${additionalArgs}")
        }

        try {
            maven "${cmd.toString()}"
            checkstyleResult = new CheckstyleResult(passed: true)
        } catch (Exception e) {
            checkstyleResult = new CheckstyleResult(passed: false, message: e.getMessage())
        }

        // Archive and publish results
        publishCheckstyleResults()

        return checkstyleResult
    }

    /**
     * Run PMD analysis.
     *
     * @param additionalArgs Additional Maven arguments
     * @return PmdResult object
     */
    PmdResult runPmd(String additionalArgs = "") {
        script.echo "Running PMD analysis (version: ${pmdVersion})"

        StringBuilder cmd = new StringBuilder()
        cmd.append("pmd:pmd pmd:cpd")
        cmd.append(" -Dpmd.version=${pmdVersion}")

        if (pmdRulesetFile && script.fileExists(pmdRulesetFile)) {
            cmd.append(" -Dpmd.rulesets=${pmdRulesetFile}")
        }

        if (additionalArgs) {
            cmd.append(" ${additionalArgs}")
        }

        try {
            maven "${cmd.toString()}"
            pmdResult = new PmdResult(passed: true)
        } catch (Exception e) {
            pmdResult = new PmdResult(passed: false, message: e.getMessage())
        }

        // Archive and publish results
        publishPmdResults()

        return pmdResult
    }

    /**
     * Run SpotBugs analysis.
     *
     * @param additionalArgs Additional Maven arguments
     * @return SpotbugsResult object
     */
    SpotbugsResult runSpotbugs(String additionalArgs = "") {
        script.echo "Running SpotBugs analysis (version: ${spotbugsVersion})"

        StringBuilder cmd = new StringBuilder()
        cmd.append("spotbugs:spotbugs")
        cmd.append(" -Dspotbugs.version=${spotbugsVersion}")

        if (spotbugsExcludeFile && script.fileExists(spotbugsExcludeFile)) {
            cmd.append(" -Dspotbugs.excludeFilterFile=${spotbugsExcludeFile}")
        }

        if (additionalArgs) {
            cmd.append(" ${additionalArgs}")
        }

        try {
            maven "${cmd.toString()}"
            spotbugsResult = new SpotbugsResult(passed: true)
        } catch (Exception e) {
            spotbugsResult = new SpotbugsResult(passed: false, message: e.getMessage())
        }

        // Archive and publish results
        publishSpotbugsResults()

        return spotbugsResult
    }

    /**
     * Run all code quality checks.
     *
     * @param parallel Whether to run checks in parallel (requires compiled classes)
     * @return OverallResult object
     */
    OverallResult runAll(boolean parallel = false) {
        script.echo "Running all code quality checks"

        if (parallel) {
            script.parallel(
                'Checkstyle': { runCheckstyle() },
                'PMD': { runPmd() },
                'SpotBugs': { runSpotbugs() }
            )
        } else {
            runCheckstyle()
            runPmd()
            runSpotbugs()
        }

        return getOverallResult()
    }

    /**
     * Run static analysis (Checkstyle + PMD) - can run before compilation.
     *
     * @return OverallResult object
     */
    OverallResult runStaticAnalysis() {
        script.echo "Running static code analysis (Checkstyle + PMD)"

        script.parallel(
            'Checkstyle': { runCheckstyle() },
            'PMD': { runPmd() }
        )

        OverallResult result = new OverallResult()
        result.checkstyle = checkstyleResult
        result.pmd = pmdResult
        result.passed = (checkstyleResult?.passed ?: true) && (pmdResult?.passed ?: true)

        return result
    }

    /**
     * Run bytecode analysis (SpotBugs) - requires compiled classes.
     *
     * @return SpotbugsResult object
     */
    SpotbugsResult runBytecodeAnalysis() {
        script.echo "Running bytecode analysis (SpotBugs)"
        return runSpotbugs()
    }

    /**
     * Get overall result from all analyses.
     *
     * @return OverallResult object
     */
    OverallResult getOverallResult() {
        OverallResult result = new OverallResult()
        result.checkstyle = checkstyleResult
        result.pmd = pmdResult
        result.spotbugs = spotbugsResult

        boolean allPassed = true
        if (checkstyleResult != null) allPassed = allPassed && checkstyleResult.passed
        if (pmdResult != null) allPassed = allPassed && pmdResult.passed
        if (spotbugsResult != null) allPassed = allPassed && spotbugsResult.passed

        result.passed = allPassed

        return result
    }

    private void publishCheckstyleResults() {
        script.archiveArtifacts artifacts: '**/checkstyle-result.xml', allowEmptyArchive: true

        try {
            script.recordIssues(
                enabledForFailure: true,
                tools: [script.checkStyle(pattern: '**/checkstyle-result.xml')],
                qualityGates: [
                    [threshold: checkstyleWarningThreshold, type: 'TOTAL', unstable: true],
                    [threshold: checkstyleErrorThreshold, type: 'TOTAL', unstable: false]
                ]
            )
        } catch (Exception e) {
            script.echo "Warnings-NG plugin not available for Checkstyle results: ${e.getMessage()}"
        }
    }

    private void publishPmdResults() {
        script.archiveArtifacts artifacts: '**/pmd.xml,**/cpd.xml', allowEmptyArchive: true

        try {
            script.recordIssues(
                enabledForFailure: true,
                tools: [
                    script.pmdParser(pattern: '**/pmd.xml'),
                    script.cpd(pattern: '**/cpd.xml')
                ],
                qualityGates: [
                    [threshold: pmdWarningThreshold, type: 'TOTAL', unstable: true],
                    [threshold: pmdErrorThreshold, type: 'TOTAL', unstable: false]
                ]
            )
        } catch (Exception e) {
            script.echo "Warnings-NG plugin not available for PMD results: ${e.getMessage()}"
        }
    }

    private void publishSpotbugsResults() {
        script.archiveArtifacts artifacts: '**/spotbugsXml.xml', allowEmptyArchive: true

        try {
            script.recordIssues(
                enabledForFailure: true,
                tools: [script.spotBugs(pattern: '**/spotbugsXml.xml')],
                qualityGates: [
                    [threshold: spotbugsWarningThreshold, type: 'TOTAL', unstable: true],
                    [threshold: spotbugsErrorThreshold, type: 'TOTAL', unstable: false]
                ]
            )
        } catch (Exception e) {
            script.echo "Warnings-NG plugin not available for SpotBugs results: ${e.getMessage()}"
        }
    }

    /**
     * Result of Checkstyle analysis.
     */
    static class CheckstyleResult implements Serializable {
        boolean passed = false
        String message = ""
        int errorCount = 0
        int warningCount = 0
        int infoCount = 0
    }

    /**
     * Result of PMD analysis.
     */
    static class PmdResult implements Serializable {
        boolean passed = false
        String message = ""
        int violationCount = 0
        int duplications = 0
    }

    /**
     * Result of SpotBugs analysis.
     */
    static class SpotbugsResult implements Serializable {
        boolean passed = false
        String message = ""
        int bugCount = 0
        int highPriorityBugs = 0
        int normalPriorityBugs = 0
        int lowPriorityBugs = 0
    }

    /**
     * Overall result combining all analyses.
     */
    static class OverallResult implements Serializable {
        boolean passed = false
        CheckstyleResult checkstyle = null
        PmdResult pmd = null
        SpotbugsResult spotbugs = null

        String getSummary() {
            StringBuilder sb = new StringBuilder()
            sb.append("Code Quality Summary:\n")
            if (checkstyle != null) {
                sb.append("  Checkstyle: ${checkstyle.passed ? 'PASSED' : 'FAILED'}\n")
            }
            if (pmd != null) {
                sb.append("  PMD: ${pmd.passed ? 'PASSED' : 'FAILED'}\n")
            }
            if (spotbugs != null) {
                sb.append("  SpotBugs: ${spotbugs.passed ? 'PASSED' : 'FAILED'}\n")
            }
            sb.append("  Overall: ${passed ? 'PASSED' : 'FAILED'}")
            return sb.toString()
        }
    }
}
