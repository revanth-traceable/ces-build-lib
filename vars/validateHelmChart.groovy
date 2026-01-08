package com.cloudogu.ces.cesbuildlib

/**
 * Validate Helm charts including lint, template rendering, and schema validation.
 *
 * Example usage:
 * <pre>
 *     validateHelmChart(
 *         cesBuildLib: cesBuildLib,
 *         chartDir: 'charts/myapp',
 *         valuesFile: 'values-ci.yaml',
 *         strict: true
 *     )
 * </pre>
 *
 * @param config Map containing:
 *   - cesBuildLib: (required) The ces-build-lib instance
 *   - chartDir: Path to Helm chart directory (default: '.')
 *   - valuesFile: Values file for validation (optional)
 *   - strict: Enable strict linting (default: true)
 *   - namespace: Kubernetes namespace for template rendering (default: 'default')
 *   - releaseName: Helm release name for template rendering (optional)
 *   - kubernetesVersion: K8s version for schema validation (default: '1.29.0')
 *   - runLint: Run helm lint (default: true)
 *   - runTemplate: Run helm template (default: true)
 *   - runValidate: Run helm template --validate (default: true)
 *   - packageChart: Package the chart after validation (default: false)
 *   - helmVersion: Helm version to use (default: '3.14.0')
 * @return boolean - true if all validations pass
 */
def call(Map config = [:]) {
    def cesBuildLib = config.cesBuildLib

    if (!cesBuildLib) {
        error "cesBuildLib is required"
    }

    String chartDir = config.chartDir ?: '.'
    String valuesFile = config.valuesFile
    boolean strict = config.strict != false
    String namespace = config.namespace ?: 'default'
    String releaseName = config.releaseName
    String kubernetesVersion = config.kubernetesVersion ?: '1.29.0'
    boolean runLint = config.runLint != false
    boolean runTemplate = config.runTemplate != false
    boolean runValidate = config.runValidate != false
    boolean packageChart = config.packageChart ?: false
    String helmVersion = config.helmVersion ?: cesBuildLib.Helm.DEFAULT_HELM_VERSION

    // Check if chart directory exists
    if (!fileExists("${chartDir}/Chart.yaml")) {
        echo "No Chart.yaml found in ${chartDir}, skipping Helm validation"
        return true
    }

    echo "=== Helm Chart Validation ==="
    echo "Chart directory: ${chartDir}"
    echo "Values file: ${valuesFile ?: 'default'}"
    echo "Strict mode: ${strict}"
    echo "Kubernetes version: ${kubernetesVersion}"

    def helm = new cesBuildLib.Helm(this, helmVersion)
        .withChartDir(chartDir)
        .withNamespace(namespace)
        .withKubernetesVersion(kubernetesVersion)

    if (releaseName) {
        helm.withReleaseName(releaseName)
    }

    boolean allPassed = true
    String chartName = helm.getChartName()
    String chartVersion = helm.getChartVersion()

    echo "Chart: ${chartName} v${chartVersion}"

    stage('Helm Validation') {
        // Update dependencies first if requirements.yaml or Chart.yaml has dependencies
        if (fileExists("${chartDir}/Chart.lock") || fileExists("${chartDir}/requirements.yaml")) {
            echo "Updating Helm dependencies..."
            helm.dependencyUpdate()
        }

        def validations = [:]

        if (runLint) {
            validations['Helm Lint'] = {
                def lintResult = helm.lint(valuesFile, strict)
                if (!lintResult.passed) {
                    allPassed = false
                }
            }
        }

        if (runTemplate) {
            validations['Helm Template'] = {
                def templateResult = helm.template(valuesFile)
                if (!templateResult.passed) {
                    allPassed = false
                }
            }
        }

        if (runValidate) {
            validations['Helm Validate'] = {
                def validateResult = helm.validate(valuesFile)
                if (!validateResult.passed) {
                    allPassed = false
                }
            }
        }

        // Run validations in parallel
        if (validations.size() > 1) {
            parallel validations
        } else {
            validations.each { name, action ->
                echo "Running ${name}..."
                action()
            }
        }

        // Package chart if requested and validation passed
        if (packageChart && allPassed) {
            echo "Packaging Helm chart..."
            helm.packageChart('helm-packages')
        }
    }

    if (allPassed) {
        echo "All Helm validations passed for ${chartName}"
    } else {
        unstable "Some Helm validations failed for ${chartName}"
    }

    return allPassed
}
