package com.cloudogu.ces.cesbuildlib

/**
 * NPM/Node.js build support for frontend projects or full-stack applications.
 *
 * Provides methods for running npm install, build, test, lint, and security audit.
 * Supports running npm commands inside Docker containers for consistent builds.
 *
 * @see https://docs.npmjs.com/cli
 */
class Npm implements Serializable {
    private script
    private Docker docker

    static final String DEFAULT_NODE_VERSION = "20"
    static final String DEFAULT_NODE_IMAGE = "node"

    private String nodeVersion
    private String nodeImage
    private String workDir = "."
    private String npmRegistry = null
    private String cacheDir = ".npm-cache"

    // Build configuration
    private boolean useCI = true  // Use npm ci instead of npm install
    private boolean auditEnabled = true
    private String auditLevel = "high"  // low, moderate, high, critical

    // Do not use DEFAULT_NODE_VERSION here, as it will lead to java.lang.VerifyError
    Npm(script, String nodeVersion = "20", Docker docker = new Docker(script)) {
        this.script = script
        this.nodeVersion = nodeVersion
        this.nodeImage = DEFAULT_NODE_IMAGE
        this.docker = docker
    }

    /**
     * Set the working directory for npm commands.
     *
     * @param dir Path to the directory containing package.json
     */
    Npm withWorkDir(String dir) {
        this.workDir = dir
        return this
    }

    /**
     * Set a custom npm registry.
     *
     * @param registry NPM registry URL
     */
    Npm withRegistry(String registry) {
        this.npmRegistry = registry
        return this
    }

    /**
     * Enable or disable npm ci (clean install) mode.
     *
     * @param useCI true to use npm ci, false to use npm install
     */
    Npm withCleanInstall(boolean useCI) {
        this.useCI = useCI
        return this
    }

    /**
     * Configure npm audit settings.
     *
     * @param enabled Whether to run npm audit
     * @param level Minimum audit level to report (low, moderate, high, critical)
     */
    Npm withAudit(boolean enabled, String level = "high") {
        this.auditEnabled = enabled
        this.auditLevel = level
        return this
    }

    /**
     * Install dependencies using npm ci or npm install.
     *
     * @param additionalArgs Additional arguments to pass to npm
     * @return true if successful
     */
    boolean install(String additionalArgs = "") {
        script.echo "Installing npm dependencies in: ${workDir}"

        String command = useCI ? "npm ci" : "npm install"
        if (additionalArgs) {
            command += " ${additionalArgs}"
        }

        Integer exitCode = runNpmCommand(command)

        if (exitCode != 0) {
            script.error "npm install failed with exit code: ${exitCode}"
            return false
        }

        script.echo "npm dependencies installed successfully"
        return true
    }

    /**
     * Run npm build script.
     *
     * @param buildScript The npm script to run (defaults to "build")
     * @param additionalArgs Additional arguments
     * @return true if successful
     */
    boolean build(String buildScript = "build", String additionalArgs = "") {
        script.echo "Running npm build: ${buildScript}"

        String command = "npm run ${buildScript}"
        if (additionalArgs) {
            command += " -- ${additionalArgs}"
        }

        Integer exitCode = runNpmCommand(command)

        if (exitCode != 0) {
            script.error "npm build failed with exit code: ${exitCode}"
            return false
        }

        script.echo "npm build completed successfully"
        return true
    }

    /**
     * Run npm test script.
     *
     * @param testScript The npm script to run (defaults to "test")
     * @param additionalArgs Additional arguments
     * @return TestResult object
     */
    TestResult test(String testScript = "test", String additionalArgs = "") {
        script.echo "Running npm tests: ${testScript}"

        String command = "npm run ${testScript}"
        if (additionalArgs) {
            command += " -- ${additionalArgs}"
        }

        Integer exitCode = runNpmCommand(command)

        TestResult result = new TestResult()
        result.passed = (exitCode == 0)
        result.message = result.passed ? "All tests passed" : "Tests failed"

        if (!result.passed) {
            script.unstable "npm test: ${result.message}"
        } else {
            script.echo "npm test: ${result.message}"
        }

        return result
    }

    /**
     * Run npm lint script.
     *
     * @param lintScript The npm script to run (defaults to "lint")
     * @param fix Whether to run with --fix flag
     * @return LintResult object
     */
    LintResult lint(String lintScript = "lint", boolean fix = false) {
        script.echo "Running npm lint: ${lintScript}"

        String command = "npm run ${lintScript}"
        if (fix) {
            command += " -- --fix"
        }

        Integer exitCode = runNpmCommand(command)

        LintResult result = new LintResult()
        result.passed = (exitCode == 0)
        result.message = result.passed ? "Linting passed" : "Linting found issues"

        if (!result.passed) {
            script.unstable "npm lint: ${result.message}"
        } else {
            script.echo "npm lint: ${result.message}"
        }

        return result
    }

    /**
     * Run npm security audit.
     *
     * @param level Minimum vulnerability level to report
     * @param fix Whether to attempt automatic fixes
     * @return AuditResult object
     */
    AuditResult audit(String level = null, boolean fix = false) {
        if (!auditEnabled) {
            script.echo "npm audit is disabled, skipping"
            AuditResult result = new AuditResult()
            result.passed = true
            result.message = "Audit skipped"
            return result
        }

        String auditLevelToUse = level ?: this.auditLevel
        script.echo "Running npm security audit (level: ${auditLevelToUse})"

        String command = fix ? "npm audit fix" : "npm audit"
        command += " --audit-level=${auditLevelToUse}"

        // npm audit returns non-zero if vulnerabilities found, capture JSON output
        String jsonCommand = "${command} --json || true"
        String auditOutput = ""

        docker.image("${nodeImage}:${nodeVersion}")
            .inside("-v ${script.env.WORKSPACE}:/app -w /app/${workDir} ${getRegistryEnv()}") {
                auditOutput = script.sh(script: jsonCommand, returnStdout: true).trim()
            }

        // Run actual audit for exit code
        Integer exitCode = runNpmCommand(command + " || true")

        AuditResult result = new AuditResult()
        result.passed = !auditOutput.contains('"vulnerabilities"') || auditOutput.contains('"total":0')
        result.message = result.passed ? "No vulnerabilities found" : "Vulnerabilities detected"
        result.rawOutput = auditOutput

        // Write audit report
        script.writeFile file: "${workDir}/npm-audit-report.json", text: auditOutput
        script.archiveArtifacts artifacts: "${workDir}/npm-audit-report.json", allowEmptyArchive: true

        if (!result.passed) {
            script.unstable "npm audit: ${result.message}"
        } else {
            script.echo "npm audit: ${result.message}"
        }

        return result
    }

    /**
     * Run a custom npm script.
     *
     * @param scriptName Name of the npm script
     * @param additionalArgs Additional arguments
     * @return exit code
     */
    Integer run(String scriptName, String additionalArgs = "") {
        script.echo "Running npm script: ${scriptName}"

        String command = "npm run ${scriptName}"
        if (additionalArgs) {
            command += " -- ${additionalArgs}"
        }

        return runNpmCommand(command)
    }

    /**
     * Execute a raw npm command.
     *
     * @param command The npm command to execute
     * @return exit code
     */
    Integer exec(String command) {
        return runNpmCommand(command)
    }

    /**
     * Get the package version from package.json.
     *
     * @return version string
     */
    String getVersion() {
        String packageJson = "${workDir}/package.json"
        if (script.fileExists(packageJson)) {
            return script.sh(
                script: "node -p \"require('./${packageJson}').version\"",
                returnStdout: true
            ).trim()
        }
        return "0.0.0"
    }

    /**
     * Get the package name from package.json.
     *
     * @return package name
     */
    String getName() {
        String packageJson = "${workDir}/package.json"
        if (script.fileExists(packageJson)) {
            return script.sh(
                script: "node -p \"require('./${packageJson}').name\"",
                returnStdout: true
            ).trim()
        }
        return "unknown"
    }

    /**
     * Check if package.json exists in the work directory.
     *
     * @return true if package.json exists
     */
    boolean hasPackageJson() {
        return script.fileExists("${workDir}/package.json")
    }

    /**
     * Check if package-lock.json exists in the work directory.
     *
     * @return true if package-lock.json exists
     */
    boolean hasLockFile() {
        return script.fileExists("${workDir}/package-lock.json")
    }

    /**
     * Full build pipeline: install, lint, test, build.
     *
     * @param options Map of options (skipTests, skipLint, buildScript, testScript, lintScript)
     * @return true if all steps pass
     */
    boolean fullBuild(Map options = [:]) {
        boolean skipTests = options.skipTests ?: false
        boolean skipLint = options.skipLint ?: false
        String buildScript = options.buildScript ?: "build"
        String testScript = options.testScript ?: "test"
        String lintScript = options.lintScript ?: "lint"

        script.echo "Starting full npm build pipeline"

        // Install
        if (!install()) {
            return false
        }

        // Lint (if not skipped and script exists)
        if (!skipLint) {
            LintResult lintResult = lint(lintScript)
            if (!lintResult.passed) {
                script.echo "Lint failed but continuing..."
            }
        }

        // Test (if not skipped)
        if (!skipTests) {
            TestResult testResult = test(testScript)
            if (!testResult.passed) {
                return false
            }
        }

        // Build
        if (!build(buildScript)) {
            return false
        }

        // Audit
        audit()

        script.echo "Full npm build pipeline completed successfully"
        return true
    }

    private Integer runNpmCommand(String command) {
        String dockerImage = "${nodeImage}:${nodeVersion}"

        return docker.image(dockerImage)
            .inside("-v ${script.env.WORKSPACE}:/app -w /app/${workDir} ${getRegistryEnv()}") {
                script.sh(script: command, returnStatus: true)
            }
    }

    private String getRegistryEnv() {
        if (npmRegistry) {
            return "-e NPM_CONFIG_REGISTRY=${npmRegistry}"
        }
        return ""
    }

    /**
     * Result of npm test operation.
     */
    static class TestResult implements Serializable {
        boolean passed = false
        String message = ""
        int totalTests = 0
        int passedTests = 0
        int failedTests = 0
    }

    /**
     * Result of npm lint operation.
     */
    static class LintResult implements Serializable {
        boolean passed = false
        String message = ""
        int errorCount = 0
        int warningCount = 0
    }

    /**
     * Result of npm audit operation.
     */
    static class AuditResult implements Serializable {
        boolean passed = false
        String message = ""
        String rawOutput = ""
        int criticalCount = 0
        int highCount = 0
        int moderateCount = 0
        int lowCount = 0
    }
}
