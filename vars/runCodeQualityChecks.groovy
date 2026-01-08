package com.cloudogu.ces.cesbuildlib

/**
 * Run code quality analysis including Checkstyle, PMD, and SpotBugs.
 *
 * Example usage:
 * <pre>
 *     runCodeQualityChecks(
 *         maven: mvn,
 *         runCheckstyle: true,
 *         runPmd: true,
 *         runSpotbugs: true,
 *         parallel: true
 *     )
 * </pre>
 *
 * @param config Map containing:
 *   - maven: (required) Maven instance for running analysis
 *   - runCheckstyle: Run Checkstyle analysis (default: true)
 *   - runPmd: Run PMD analysis (default: true)
 *   - runSpotbugs: Run SpotBugs analysis (default: true)
 *   - parallel: Run analyses in parallel (default: false, SpotBugs requires compiled code)
 *   - checkstyleConfig: Custom Checkstyle config file (optional)
 *   - pmdRuleset: Custom PMD ruleset file (optional)
 *   - spotbugsExclude: SpotBugs exclude filter file (optional)
 *   - checkstyleVersion: Checkstyle version (optional)
 *   - pmdVersion: PMD version (optional)
 *   - spotbugsVersion: SpotBugs version (optional)
 * @return CodeQuality.OverallResult object
 */
def call(Map config = [:]) {
    def maven = config.maven

    if (!maven) {
        error "maven instance is required"
    }

    boolean runCheckstyle = config.runCheckstyle != false
    boolean runPmd = config.runPmd != false
    boolean runSpotbugs = config.runSpotbugs != false
    boolean runParallel = config.parallel ?: false

    echo "=== Code Quality Analysis ==="
    echo "Checkstyle: ${runCheckstyle}"
    echo "PMD: ${runPmd}"
    echo "SpotBugs: ${runSpotbugs}"
    echo "Parallel execution: ${runParallel}"

    def codeQuality = new CodeQuality(this, maven)

    // Configure tools if custom settings provided
    if (config.checkstyleConfig || config.checkstyleVersion) {
        codeQuality.withCheckstyle(
            config.checkstyleVersion,
            config.checkstyleConfig,
            config.checkstyleWarningThreshold ?: 50,
            config.checkstyleErrorThreshold ?: 10
        )
    }

    if (config.pmdRuleset || config.pmdVersion) {
        codeQuality.withPmd(
            config.pmdVersion,
            config.pmdRuleset,
            config.pmdWarningThreshold ?: 50,
            config.pmdErrorThreshold ?: 10
        )
    }

    if (config.spotbugsExclude || config.spotbugsVersion) {
        codeQuality.withSpotbugs(
            config.spotbugsVersion,
            config.spotbugsExclude,
            config.spotbugsWarningThreshold ?: 20,
            config.spotbugsErrorThreshold ?: 5
        )
    }

    def result

    stage('Code Quality Analysis') {
        if (runParallel && runCheckstyle && runPmd && runSpotbugs) {
            // Run all in parallel (assumes code is already compiled for SpotBugs)
            result = codeQuality.runAll(true)
        } else if (runParallel && runCheckstyle && runPmd && !runSpotbugs) {
            // Run static analysis in parallel
            result = codeQuality.runStaticAnalysis()
        } else {
            // Run sequentially
            def analyses = [:]

            if (runCheckstyle) {
                analyses['Checkstyle'] = { codeQuality.runCheckstyle() }
            }

            if (runPmd) {
                analyses['PMD'] = { codeQuality.runPmd() }
            }

            if (runSpotbugs) {
                analyses['SpotBugs'] = { codeQuality.runSpotbugs() }
            }

            if (runParallel && analyses.size() > 1) {
                parallel analyses
            } else {
                analyses.each { name, action ->
                    echo "Running ${name}..."
                    action()
                }
            }

            result = codeQuality.getOverallResult()
        }

        echo result.getSummary()
    }

    return result
}
