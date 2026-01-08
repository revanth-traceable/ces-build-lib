package com.cloudogu.ces.cesbuildlib

/**
 * OWASP Dependency-Check integration for scanning project dependencies for known vulnerabilities.
 *
 * Uses the OWASP Dependency-Check tool to identify project dependencies and check if there are
 * any known, publicly disclosed vulnerabilities (CVEs).
 *
 * @see https://owasp.org/www-project-dependency-check/
 */
class DependencyCheck implements Serializable {
    private script
    private Docker docker

    static final String DEFAULT_DEPENDENCY_CHECK_VERSION = "8.4.3"
    static final String DEFAULT_DEPENDENCY_CHECK_IMAGE = "owasp/dependency-check"

    private String version
    private String image
    private String reportDir = "dependency-check-report"
    private String dataDir = ".dependency-check-data"

    // CVSS score threshold (0-10). Vulnerabilities with score >= threshold will fail/warn
    private float failOnCvssAbove = 7.0
    private float warnOnCvssAbove = 4.0

    // Suppression file for false positives
    private String suppressionFile = null

    // NVD API key (required since 2023 for NVD database access)
    // Get your free key at: https://nvd.nist.gov/developers/request-an-api-key
    private String nvdApiKeyCredentialsId = null

    // Do not use DEFAULT_DEPENDENCY_CHECK_VERSION here, as it will lead to java.lang.VerifyError
    DependencyCheck(script, String version = "8.4.3", Docker docker = new Docker(script)) {
        this.script = script
        this.version = version
        this.image = DEFAULT_DEPENDENCY_CHECK_IMAGE
        this.docker = docker
    }

    /**
     * Configure the CVSS score thresholds for failing or warning the build.
     *
     * @param failThreshold CVSS score (0-10) at which the build should fail
     * @param warnThreshold CVSS score (0-10) at which the build should be marked unstable
     */
    DependencyCheck withCvssThresholds(float failThreshold, float warnThreshold) {
        this.failOnCvssAbove = failThreshold
        this.warnOnCvssAbove = warnThreshold
        return this
    }

    /**
     * Set a suppression file to exclude known false positives.
     *
     * @param suppressionFilePath Path to the suppression XML file
     */
    DependencyCheck withSuppressionFile(String suppressionFilePath) {
        this.suppressionFile = suppressionFilePath
        return this
    }

    /**
     * Set custom report directory.
     *
     * @param dir Directory where reports will be generated
     */
    DependencyCheck withReportDir(String dir) {
        this.reportDir = dir
        return this
    }

    /**
     * Set NVD API key credentials ID.
     * Required since 2023 for accessing the NVD database.
     * Get your free API key at: https://nvd.nist.gov/developers/request-an-api-key
     *
     * @param credentialsId Jenkins credentials ID containing the NVD API key (secret text)
     */
    DependencyCheck withNvdApiKey(String credentialsId) {
        this.nvdApiKeyCredentialsId = credentialsId
        return this
    }

    /**
     * Scan a Maven project for vulnerable dependencies.
     *
     * @param scanPath Path to scan (defaults to current directory)
     * @param additionalArgs Additional arguments to pass to dependency-check
     * @return ScanResult object containing scan status and details
     */
    ScanResult scanMavenProject(String scanPath = ".", String additionalArgs = "") {
        return scan(scanPath, "pom.xml", additionalArgs)
    }

    /**
     * Scan a Gradle project for vulnerable dependencies.
     *
     * @param scanPath Path to scan (defaults to current directory)
     * @param additionalArgs Additional arguments to pass to dependency-check
     * @return ScanResult object containing scan status and details
     */
    ScanResult scanGradleProject(String scanPath = ".", String additionalArgs = "") {
        return scan(scanPath, "build.gradle", additionalArgs)
    }

    /**
     * Scan an NPM project for vulnerable dependencies.
     *
     * @param scanPath Path to scan (defaults to current directory)
     * @param additionalArgs Additional arguments to pass to dependency-check
     * @return ScanResult object containing scan status and details
     */
    ScanResult scanNpmProject(String scanPath = ".", String additionalArgs = "") {
        return scan(scanPath, "package-lock.json", additionalArgs + " --enableExperimental")
    }

    /**
     * Generic scan method for any project type.
     *
     * @param scanPath Path to scan
     * @param projectFile Optional project file to verify exists
     * @param additionalArgs Additional arguments
     * @return ScanResult object
     */
    ScanResult scan(String scanPath = ".", String projectFile = null, String additionalArgs = "") {
        script.echo "Starting OWASP Dependency-Check scan on: ${scanPath}"

        // Verify project file exists if specified
        if (projectFile) {
            def fileExists = script.fileExists("${scanPath}/${projectFile}")
            if (!fileExists) {
                script.echo "Warning: ${projectFile} not found in ${scanPath}"
            }
        }

        // Create report directory
        script.sh "mkdir -p ${reportDir}"
        script.sh "mkdir -p ${dataDir}"

        // Build the command
        String command = buildCommand(scanPath, additionalArgs)

        // Run dependency-check in Docker
        Integer exitCode = runInDocker(command)

        // Process results
        ScanResult result = processResults(exitCode)

        // Archive reports
        archiveReports()

        return result
    }

    private String buildCommand(String scanPath, String additionalArgs) {
        StringBuilder cmd = new StringBuilder()
        cmd.append("--scan ${scanPath}")
        cmd.append(" --format HTML --format JSON --format XML")
        cmd.append(" --out /report")
        cmd.append(" --failOnCVSS ${failOnCvssAbove}")
        cmd.append(" --data /data")

        if (suppressionFile && script.fileExists(suppressionFile)) {
            cmd.append(" --suppression /src/${suppressionFile}")
        }

        if (additionalArgs) {
            cmd.append(" ${additionalArgs}")
        }

        return cmd.toString()
    }

    private Integer runInDocker(String command) {
        String dockerImage = "${image}:${version}"

        script.echo "Running Dependency-Check with image: ${dockerImage}"

        if (nvdApiKeyCredentialsId) {
            // Run with NVD API key from Jenkins credentials
            return script.withCredentials([script.string(credentialsId: nvdApiKeyCredentialsId, variable: 'NVD_API_KEY')]) {
                docker.image(dockerImage)
                    .inside("-v ${script.env.WORKSPACE}:/src -v ${script.env.WORKSPACE}/${reportDir}:/report -v ${script.env.WORKSPACE}/${dataDir}:/data -e NVD_API_KEY=${script.env.NVD_API_KEY} --entrypoint=''") {
                        script.sh(script: "/usr/share/dependency-check/bin/dependency-check.sh ${command} --nvdApiKey ${script.env.NVD_API_KEY}", returnStatus: true)
                    }
            }
        } else {
            // Run without NVD API key (will use cached data or fail if no cache exists)
            script.echo "WARNING: No NVD API key configured. Dependency-Check may fail or use stale data."
            script.echo "Get a free API key at: https://nvd.nist.gov/developers/request-an-api-key"
            return docker.image(dockerImage)
                .inside("-v ${script.env.WORKSPACE}:/src -v ${script.env.WORKSPACE}/${reportDir}:/report -v ${script.env.WORKSPACE}/${dataDir}:/data --entrypoint=''") {
                    script.sh(script: "/usr/share/dependency-check/bin/dependency-check.sh ${command}", returnStatus: true)
                }
        }
    }

    private ScanResult processResults(Integer exitCode) {
        ScanResult result = new ScanResult()

        switch (exitCode) {
            case 0:
                result.status = ScanStatus.PASSED
                result.message = "No vulnerabilities found above CVSS threshold ${failOnCvssAbove}"
                script.echo "Dependency-Check: ${result.message}"
                break
            case 1:
                result.status = ScanStatus.FAILED
                result.message = "Vulnerabilities found with CVSS score >= ${failOnCvssAbove}"
                script.error "Dependency-Check: ${result.message}. See report for details."
                break
            default:
                result.status = ScanStatus.ERROR
                result.message = "Dependency-Check scan failed with exit code: ${exitCode}"
                script.error result.message
        }

        return result
    }

    private void archiveReports() {
        script.archiveArtifacts artifacts: "${reportDir}/*", allowEmptyArchive: true

        // Publish HTML report if plugin is available
        try {
            script.publishHTML([
                allowMissing: true,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: reportDir,
                reportFiles: 'dependency-check-report.html',
                reportName: 'OWASP Dependency-Check Report'
            ])
        } catch (Exception e) {
            script.echo "HTML Publisher plugin not available, skipping HTML report publishing"
        }
    }

    /**
     * Result of a dependency check scan.
     */
    static class ScanResult implements Serializable {
        ScanStatus status = ScanStatus.UNKNOWN
        String message = ""
        int vulnerabilityCount = 0
        int criticalCount = 0
        int highCount = 0
        int mediumCount = 0
        int lowCount = 0

        boolean isPassed() {
            return status == ScanStatus.PASSED
        }

        boolean isFailed() {
            return status == ScanStatus.FAILED
        }
    }

    /**
     * Possible scan statuses.
     */
    static enum ScanStatus {
        PASSED,
        FAILED,
        ERROR,
        UNKNOWN
    }
}
