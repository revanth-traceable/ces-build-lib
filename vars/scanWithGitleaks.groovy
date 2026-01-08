package com.cloudogu.ces.cesbuildlib

/**
 * Scan repository for secrets and credentials using Gitleaks.
 *
 * Example usage:
 * <pre>
 *     scanWithGitleaks(
 *         scanPath: '.',
 *         scanFullHistory: true,
 *         strategy: 'FAIL'
 *     )
 * </pre>
 *
 * @param config Map containing:
 *   - scanPath: Path to scan (default: '.')
 *   - scanMode: 'detect' (git history), 'protect' (current files), or 'files' (non-git) (default: 'detect')
 *   - scanFullHistory: Scan full git history (default: true)
 *   - commitRange: Specific commit range to scan (optional, e.g., 'HEAD~10..HEAD')
 *   - strategy: FAIL, UNSTABLE, or IGNORE (default: 'FAIL')
 *   - configFile: Custom .gitleaks.toml config file (optional)
 *   - baselineFile: Baseline file for known issues (optional)
 *   - reportFormat: json, csv, or sarif (default: 'json')
 *   - reportDir: Directory for reports (default: 'gitleaks-report')
 *   - version: Gitleaks version (default: '8.18.1')
 *   - additionalArgs: Additional command line arguments (optional)
 * @return Gitleaks.ScanResult object
 */
def call(Map config = [:]) {
    String scanPath = config.scanPath ?: '.'
    String scanMode = config.scanMode ?: 'detect'
    boolean scanFullHistory = config.scanFullHistory != false
    String commitRange = config.commitRange
    String strategy = config.strategy ?: GitleaksScanStrategy.FAIL
    String configFile = config.configFile
    String baselineFile = config.baselineFile
    String reportFormat = config.reportFormat ?: 'json'
    String reportDir = config.reportDir ?: 'gitleaks-report'
    String version = config.version ?: Gitleaks.DEFAULT_GITLEAKS_VERSION
    String additionalArgs = config.additionalArgs ?: ''

    echo "=== Gitleaks Secret Detection ==="
    echo "Scan path: ${scanPath}"
    echo "Scan mode: ${scanMode}"
    echo "Scan full history: ${scanFullHistory}"
    echo "Strategy: ${strategy}"

    def gitleaks = new Gitleaks(this, version)
        .withStrategy(strategy)
        .withReportFormat(reportFormat)
        .withReportDir(reportDir)
        .withScanRange(scanFullHistory, commitRange)

    if (configFile) {
        gitleaks.withConfig(configFile)
    }

    if (baselineFile) {
        gitleaks.withBaseline(baselineFile)
    }

    def result

    stage('Gitleaks Secret Scan') {
        switch (scanMode) {
            case 'detect':
                echo "Scanning git history for secrets..."
                result = gitleaks.detect(scanPath, additionalArgs)
                break
            case 'protect':
                echo "Scanning current files for secrets..."
                boolean staged = config.staged ?: false
                result = gitleaks.protect(scanPath, staged, additionalArgs)
                break
            case 'files':
                echo "Scanning files (non-git) for secrets..."
                result = gitleaks.scanFiles(scanPath, additionalArgs)
                break
            default:
                error "Unknown scan mode: ${scanMode}. Use 'detect', 'protect', or 'files'"
        }

        echo "Gitleaks scan completed: ${result.message}"

        if (result.isPassed()) {
            echo "No secrets detected in repository"
        } else if (result.hasFindings()) {
            echo "WARNING: ${result.findingsCount} potential secret(s) detected!"
            echo "Review the report in ${reportDir} for details"
        }
    }

    return result
}
