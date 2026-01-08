package com.cloudogu.ces.cesbuildlib

/**
 * Run NPM build pipeline including install, lint, test, and build.
 *
 * Example usage:
 * <pre>
 *     runNpmBuild(
 *         cesBuildLib: cesBuildLib,
 *         workDir: 'frontend',
 *         nodeVersion: '20',
 *         runTests: true,
 *         runAudit: true
 *     )
 * </pre>
 *
 * @param config Map containing:
 *   - cesBuildLib: (required) The ces-build-lib instance
 *   - workDir: Working directory containing package.json (default: '.')
 *   - nodeVersion: Node.js version (default: '20')
 *   - runTests: Run npm test (default: true)
 *   - runLint: Run npm lint (default: true)
 *   - runAudit: Run npm audit (default: true)
 *   - runBuild: Run npm build (default: true)
 *   - buildScript: npm script for build (default: 'build')
 *   - testScript: npm script for test (default: 'test')
 *   - lintScript: npm script for lint (default: 'lint')
 *   - auditLevel: Minimum audit level (default: 'high')
 *   - useCI: Use npm ci instead of npm install (default: true)
 *   - registry: Custom npm registry URL (optional)
 * @return Map with results of each step
 */
def call(Map config = [:]) {
    def cesBuildLib = config.cesBuildLib

    if (!cesBuildLib) {
        error "cesBuildLib is required"
    }

    String workDir = config.workDir ?: '.'
    String nodeVersion = config.nodeVersion ?: '20'
    boolean runTests = config.runTests != false
    boolean runLint = config.runLint != false
    boolean runAudit = config.runAudit != false
    boolean runBuild = config.runBuild != false
    String buildScript = config.buildScript ?: 'build'
    String testScript = config.testScript ?: 'test'
    String lintScript = config.lintScript ?: 'lint'
    String auditLevel = config.auditLevel ?: 'high'
    boolean useCI = config.useCI != false
    String registry = config.registry

    // Check if package.json exists
    if (!fileExists("${workDir}/package.json")) {
        echo "No package.json found in ${workDir}, skipping NPM build"
        return [skipped: true]
    }

    echo "=== NPM Build Pipeline ==="
    echo "Work directory: ${workDir}"
    echo "Node.js version: ${nodeVersion}"
    echo "Run tests: ${runTests}"
    echo "Run lint: ${runLint}"
    echo "Run audit: ${runAudit}"
    echo "Run build: ${runBuild}"

    def npm = new cesBuildLib.Npm(this, nodeVersion)
        .withWorkDir(workDir)
        .withCleanInstall(useCI)
        .withAudit(runAudit, auditLevel)

    if (registry) {
        npm.withRegistry(registry)
    }

    def results = [
        skipped: false,
        install: null,
        lint: null,
        test: null,
        audit: null,
        build: null
    ]

    String packageName = npm.getName()
    String packageVersion = npm.getVersion()

    echo "Package: ${packageName} v${packageVersion}"

    stage('NPM Install') {
        echo "Installing NPM dependencies..."
        results.install = npm.install()
        if (!results.install) {
            error "NPM install failed"
        }
    }

    // Run lint and test in parallel if both enabled
    if (runLint && runTests) {
        stage('NPM Lint & Test') {
            parallel(
                'NPM Lint': {
                    echo "Running NPM lint..."
                    results.lint = npm.lint(lintScript)
                },
                'NPM Test': {
                    echo "Running NPM tests..."
                    results.test = npm.test(testScript)
                }
            )
        }
    } else {
        if (runLint) {
            stage('NPM Lint') {
                echo "Running NPM lint..."
                results.lint = npm.lint(lintScript)
            }
        }

        if (runTests) {
            stage('NPM Test') {
                echo "Running NPM tests..."
                results.test = npm.test(testScript)
            }
        }
    }

    if (runBuild) {
        stage('NPM Build') {
            echo "Running NPM build..."
            results.build = npm.build(buildScript)
            if (!results.build) {
                error "NPM build failed"
            }
        }
    }

    if (runAudit) {
        stage('NPM Security Audit') {
            echo "Running NPM security audit..."
            results.audit = npm.audit(auditLevel)
        }
    }

    // Archive build artifacts if they exist
    if (runBuild) {
        def distDirs = ['dist', 'build', 'out']
        distDirs.each { dir ->
            if (fileExists("${workDir}/${dir}")) {
                archiveArtifacts artifacts: "${workDir}/${dir}/**/*", allowEmptyArchive: true
            }
        }
    }

    echo "=== NPM Build Pipeline Complete ==="
    echo "Package: ${packageName} v${packageVersion}"
    echo "Install: ${results.install ? 'SUCCESS' : 'FAILED'}"
    if (runLint) echo "Lint: ${results.lint?.passed ? 'PASSED' : 'ISSUES FOUND'}"
    if (runTests) echo "Test: ${results.test?.passed ? 'PASSED' : 'FAILED'}"
    if (runAudit) echo "Audit: ${results.audit?.passed ? 'PASSED' : 'VULNERABILITIES FOUND'}"
    if (runBuild) echo "Build: ${results.build ? 'SUCCESS' : 'FAILED'}"

    return results
}
