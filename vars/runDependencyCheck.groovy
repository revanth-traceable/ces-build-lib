package com.cloudogu.ces.cesbuildlib

/**
 * Run OWASP Dependency-Check analysis on a project.
 *
 * Example usage:
 * <pre>
 *     runDependencyCheck(
 *         scanPath: '.',
 *         failOnCvss: 7.0,
 *         warnOnCvss: 4.0,
 *         suppressionFile: 'dependency-check-suppression.xml'
 *     )
 * </pre>
 *
 * @param config Map containing:
 *   - scanPath: Path to scan (default: '.')
 *   - projectType: Type of project - 'maven', 'gradle', 'npm', or 'auto' (default: 'auto')
 *   - failOnCvss: CVSS score threshold to fail build (default: 7.0)
 *   - warnOnCvss: CVSS score threshold to warn (default: 4.0)
 *   - suppressionFile: Path to suppression XML file (optional)
 *   - reportDir: Directory for reports (default: 'dependency-check-report')
 *   - version: Dependency-Check version (default: '8.4.3')
 *   - additionalArgs: Additional command line arguments (optional)
 * @return DependencyCheck.ScanResult object
 */
def call(Map config = [:]) {
    String scanPath = config.scanPath ?: '.'
    String projectType = config.projectType ?: 'auto'
    float failOnCvss = config.failOnCvss ?: 7.0
    float warnOnCvss = config.warnOnCvss ?: 4.0
    String suppressionFile = config.suppressionFile
    String reportDir = config.reportDir ?: 'dependency-check-report'
    String version = config.version ?: DependencyCheck.DEFAULT_DEPENDENCY_CHECK_VERSION
    String additionalArgs = config.additionalArgs ?: ''

    echo "=== OWASP Dependency-Check Analysis ==="
    echo "Scan path: ${scanPath}"
    echo "Project type: ${projectType}"
    echo "Fail on CVSS >= ${failOnCvss}"

    def depCheck = new DependencyCheck(this, version)
        .withCvssThresholds(failOnCvss, warnOnCvss)
        .withReportDir(reportDir)

    if (suppressionFile) {
        depCheck.withSuppressionFile(suppressionFile)
    }

    def result

    stage('OWASP Dependency-Check') {
        // Auto-detect project type if needed
        if (projectType == 'auto') {
            projectType = detectProjectType(scanPath)
            echo "Auto-detected project type: ${projectType}"
        }

        switch (projectType) {
            case 'maven':
                result = depCheck.scanMavenProject(scanPath, additionalArgs)
                break
            case 'gradle':
                result = depCheck.scanGradleProject(scanPath, additionalArgs)
                break
            case 'npm':
                result = depCheck.scanNpmProject(scanPath, additionalArgs)
                break
            default:
                result = depCheck.scan(scanPath, null, additionalArgs)
        }

        echo "Dependency-Check completed: ${result.message}"
    }

    return result
}

/**
 * Detect project type based on files in the scan path.
 */
private String detectProjectType(String scanPath) {
    if (fileExists("${scanPath}/pom.xml")) {
        return 'maven'
    } else if (fileExists("${scanPath}/build.gradle") || fileExists("${scanPath}/build.gradle.kts")) {
        return 'gradle'
    } else if (fileExists("${scanPath}/package.json")) {
        return 'npm'
    }
    return 'unknown'
}
