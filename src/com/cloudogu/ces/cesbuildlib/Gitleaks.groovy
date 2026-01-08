package com.cloudogu.ces.cesbuildlib

/**
 * Gitleaks integration for detecting secrets and credentials in git repositories.
 *
 * Scans git history and current files for accidentally committed secrets like
 * API keys, passwords, tokens, private keys, etc.
 *
 * @see https://github.com/gitleaks/gitleaks
 */
class Gitleaks implements Serializable {
    private script
    private Docker docker

    static final String DEFAULT_GITLEAKS_VERSION = "8.18.1"
    static final String DEFAULT_GITLEAKS_IMAGE = "zricethezav/gitleaks"

    private String version
    private String image
    private String reportDir = "gitleaks-report"
    private String reportFormat = "json"  // json, csv, sarif

    // Scan configuration
    private String configFile = null  // Custom .gitleaks.toml
    private String baselineFile = null  // Baseline file for known issues
    private boolean scanFullHistory = true
    private String commitRange = null  // e.g., "HEAD~10..HEAD"
    private int exitCodeOnFindings = 1

    // Strategy for handling findings
    private String strategy = GitleaksScanStrategy.FAIL

    Gitleaks(script, String version = DEFAULT_GITLEAKS_VERSION, Docker docker = null) {
        this.script = script
        this.version = version
        this.image = DEFAULT_GITLEAKS_IMAGE
        this.docker = docker ?: new Docker(script)
    }

    /**
     * Set custom configuration file.
     *
     * @param configPath Path to custom .gitleaks.toml file
     */
    Gitleaks withConfig(String configPath) {
        this.configFile = configPath
        return this
    }

    /**
     * Set baseline file for known/accepted findings.
     *
     * @param baselinePath Path to baseline JSON file
     */
    Gitleaks withBaseline(String baselinePath) {
        this.baselineFile = baselinePath
        return this
    }

    /**
     * Configure scan range.
     *
     * @param fullHistory Whether to scan full git history
     * @param range Optional commit range (e.g., "HEAD~10..HEAD", "main..feature-branch")
     */
    Gitleaks withScanRange(boolean fullHistory, String range = null) {
        this.scanFullHistory = fullHistory
        this.commitRange = range
        return this
    }

    /**
     * Set the report format.
     *
     * @param format Report format: json, csv, sarif
     */
    Gitleaks withReportFormat(String format) {
        this.reportFormat = format
        return this
    }

    /**
     * Set the strategy for handling findings.
     *
     * @param strategy One of GitleaksScanStrategy values
     */
    Gitleaks withStrategy(String strategy) {
        this.strategy = strategy
        return this
    }

    /**
     * Set custom report directory.
     *
     * @param dir Directory for reports
     */
    Gitleaks withReportDir(String dir) {
        this.reportDir = dir
        return this
    }

    /**
     * Scan the repository for secrets using 'detect' mode.
     * This scans the git history for committed secrets.
     *
     * @param path Path to scan (defaults to current directory)
     * @param additionalArgs Additional gitleaks arguments
     * @return ScanResult object
     */
    ScanResult detect(String path = ".", String additionalArgs = "") {
        script.echo "Running Gitleaks secret detection on: ${path}"

        script.sh "mkdir -p ${reportDir}"

        String command = buildDetectCommand(path, additionalArgs)
        Integer exitCode = runGitleaksCommand(command)

        return processResults(exitCode, "detect")
    }

    /**
     * Scan current working directory (not git history) for secrets using 'protect' mode.
     * Useful for pre-commit or CI checks on staged/modified files.
     *
     * @param path Path to scan
     * @param staged Only scan staged files
     * @param additionalArgs Additional arguments
     * @return ScanResult object
     */
    ScanResult protect(String path = ".", boolean staged = false, String additionalArgs = "") {
        script.echo "Running Gitleaks protect scan on: ${path}"

        script.sh "mkdir -p ${reportDir}"

        String command = buildProtectCommand(path, staged, additionalArgs)
        Integer exitCode = runGitleaksCommand(command)

        return processResults(exitCode, "protect")
    }

    /**
     * Scan specific files (not git-aware).
     *
     * @param path Path to scan
     * @param additionalArgs Additional arguments
     * @return ScanResult object
     */
    ScanResult scanFiles(String path = ".", String additionalArgs = "") {
        script.echo "Running Gitleaks file scan on: ${path}"

        script.sh "mkdir -p ${reportDir}"

        String command = buildFileScanCommand(path, additionalArgs)
        Integer exitCode = runGitleaksCommand(command)

        return processResults(exitCode, "files")
    }

    /**
     * Generate a baseline file from current findings.
     * Useful for documenting known/accepted findings.
     *
     * @param path Path to scan
     * @param outputFile Output baseline file
     * @return true if baseline created successfully
     */
    boolean createBaseline(String path = ".", String outputFile = ".gitleaks-baseline.json") {
        script.echo "Creating Gitleaks baseline file: ${outputFile}"

        String command = "gitleaks detect --source ${path} --report-path ${outputFile} --report-format json --exit-code 0"

        Integer exitCode = runGitleaksCommand(command)

        if (script.fileExists(outputFile)) {
            script.echo "Baseline file created: ${outputFile}"
            return true
        }

        script.echo "No findings to create baseline from"
        return false
    }

    private String buildDetectCommand(String path, String additionalArgs) {
        StringBuilder cmd = new StringBuilder()
        cmd.append("gitleaks detect")
        cmd.append(" --source ${path}")
        cmd.append(" --report-path /report/gitleaks-report.${reportFormat}")
        cmd.append(" --report-format ${reportFormat}")
        cmd.append(" --exit-code ${exitCodeOnFindings}")
        cmd.append(" --verbose")

        if (configFile && script.fileExists(configFile)) {
            cmd.append(" --config /src/${configFile}")
        }

        if (baselineFile && script.fileExists(baselineFile)) {
            cmd.append(" --baseline-path /src/${baselineFile}")
        }

        if (!scanFullHistory) {
            cmd.append(" --no-git")
        }

        if (commitRange) {
            cmd.append(" --log-opts='${commitRange}'")
        }

        if (additionalArgs) {
            cmd.append(" ${additionalArgs}")
        }

        return cmd.toString()
    }

    private String buildProtectCommand(String path, boolean staged, String additionalArgs) {
        StringBuilder cmd = new StringBuilder()
        cmd.append("gitleaks protect")
        cmd.append(" --source ${path}")
        cmd.append(" --report-path /report/gitleaks-report.${reportFormat}")
        cmd.append(" --report-format ${reportFormat}")
        cmd.append(" --exit-code ${exitCodeOnFindings}")
        cmd.append(" --verbose")

        if (staged) {
            cmd.append(" --staged")
        }

        if (configFile && script.fileExists(configFile)) {
            cmd.append(" --config /src/${configFile}")
        }

        if (additionalArgs) {
            cmd.append(" ${additionalArgs}")
        }

        return cmd.toString()
    }

    private String buildFileScanCommand(String path, String additionalArgs) {
        StringBuilder cmd = new StringBuilder()
        cmd.append("gitleaks detect")
        cmd.append(" --source ${path}")
        cmd.append(" --no-git")
        cmd.append(" --report-path /report/gitleaks-report.${reportFormat}")
        cmd.append(" --report-format ${reportFormat}")
        cmd.append(" --exit-code ${exitCodeOnFindings}")
        cmd.append(" --verbose")

        if (configFile && script.fileExists(configFile)) {
            cmd.append(" --config /src/${configFile}")
        }

        if (additionalArgs) {
            cmd.append(" ${additionalArgs}")
        }

        return cmd.toString()
    }

    private Integer runGitleaksCommand(String command) {
        String dockerImage = "${image}:v${version}"

        script.echo "Running Gitleaks with image: ${dockerImage}"

        return docker.image(dockerImage)
            .inside("-v ${script.env.WORKSPACE}:/src -v ${script.env.WORKSPACE}/${reportDir}:/report -w /src --entrypoint=''") {
                script.sh(script: command, returnStatus: true)
            }
    }

    private ScanResult processResults(Integer exitCode, String scanType) {
        ScanResult result = new ScanResult()
        result.scanType = scanType

        String reportFile = "${reportDir}/gitleaks-report.${reportFormat}"

        switch (exitCode) {
            case 0:
                result.status = ScanStatus.PASSED
                result.message = "No secrets detected"
                result.findingsCount = 0
                script.echo "Gitleaks: ${result.message}"
                break
            case 1:
                result.status = ScanStatus.FINDINGS
                result.message = "Secrets detected in repository"
                result.findingsCount = countFindings(reportFile)

                // Apply strategy
                applyStrategy(result)
                break
            default:
                result.status = ScanStatus.ERROR
                result.message = "Gitleaks scan failed with exit code: ${exitCode}"
                script.error result.message
        }

        // Archive report
        archiveReports()

        return result
    }

    private int countFindings(String reportFile) {
        if (!script.fileExists(reportFile)) {
            return 0
        }

        try {
            if (reportFormat == "json") {
                String content = script.readFile(reportFile)
                // Simple count of findings in JSON array
                def findings = script.readJSON(text: content)
                return findings.size()
            }
        } catch (Exception e) {
            script.echo "Could not parse findings count: ${e.getMessage()}"
        }

        return -1  // Unknown count
    }

    private void applyStrategy(ScanResult result) {
        switch (strategy) {
            case GitleaksScanStrategy.IGNORE:
                script.echo "Gitleaks: ${result.message} (ignored per strategy)"
                break
            case GitleaksScanStrategy.UNSTABLE:
                script.unstable "Gitleaks: ${result.message}. Found ${result.findingsCount} potential secret(s). See report for details."
                break
            case GitleaksScanStrategy.FAIL:
            default:
                script.error "Gitleaks: ${result.message}. Found ${result.findingsCount} potential secret(s). See report for details."
        }
    }

    private void archiveReports() {
        script.archiveArtifacts artifacts: "${reportDir}/*", allowEmptyArchive: true

        // Publish HTML report if sarif format
        if (reportFormat == "sarif") {
            try {
                script.recordIssues(
                    enabledForFailure: true,
                    tools: [script.sarif(pattern: "${reportDir}/*.sarif")]
                )
            } catch (Exception e) {
                script.echo "Warnings-NG plugin not available for SARIF results: ${e.getMessage()}"
            }
        }
    }

    /**
     * Result of a Gitleaks scan.
     */
    static class ScanResult implements Serializable {
        ScanStatus status = ScanStatus.UNKNOWN
        String message = ""
        String scanType = ""
        int findingsCount = 0
        List<Finding> findings = []

        boolean isPassed() {
            return status == ScanStatus.PASSED
        }

        boolean hasFindings() {
            return status == ScanStatus.FINDINGS
        }
    }

    /**
     * Individual secret finding.
     */
    static class Finding implements Serializable {
        String description = ""
        String file = ""
        int line = 0
        String secret = ""  // Redacted
        String rule = ""
        String commit = ""
        String author = ""
        String date = ""
    }

    /**
     * Possible scan statuses.
     */
    static enum ScanStatus {
        PASSED,
        FINDINGS,
        ERROR,
        UNKNOWN
    }
}

/**
 * Strategy for handling Gitleaks findings.
 */
class GitleaksScanStrategy {
    static final String IGNORE = "IGNORE"      // Log but don't affect build
    static final String UNSTABLE = "UNSTABLE"  // Mark build as unstable
    static final String FAIL = "FAIL"          // Fail the build
}
