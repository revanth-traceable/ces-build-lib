package com.cloudogu.ces.cesbuildlib

/**
 * Helm chart operations for Kubernetes deployments.
 *
 * Provides methods for linting, packaging, templating, and validating Helm charts.
 * Supports both Helm 3 operations.
 *
 * @see https://helm.sh/docs/
 */
class Helm implements Serializable {
    private script
    private Docker docker

    static final String DEFAULT_HELM_VERSION = "3.14.0"
    static final String DEFAULT_HELM_IMAGE = "alpine/helm"

    private String version
    private String image
    private String chartDir = "."
    private String namespace = "default"
    private String releaseName = null

    // Kubernetes schema validation
    private boolean enableKubeValidation = true
    private String kubernetesVersion = "1.29.0"

    Helm(script, String version = DEFAULT_HELM_VERSION, Docker docker = null) {
        this.script = script
        this.version = version
        this.image = DEFAULT_HELM_IMAGE
        this.docker = docker ?: new Docker(script)
    }

    /**
     * Set the chart directory.
     *
     * @param dir Path to the Helm chart directory
     */
    Helm withChartDir(String dir) {
        this.chartDir = dir
        return this
    }

    /**
     * Set the Kubernetes namespace for template rendering.
     *
     * @param ns Kubernetes namespace
     */
    Helm withNamespace(String ns) {
        this.namespace = ns
        return this
    }

    /**
     * Set the release name for template rendering.
     *
     * @param name Helm release name
     */
    Helm withReleaseName(String name) {
        this.releaseName = name
        return this
    }

    /**
     * Set the Kubernetes version for schema validation.
     *
     * @param version Kubernetes version (e.g., "1.29.0")
     */
    Helm withKubernetesVersion(String version) {
        this.kubernetesVersion = version
        return this
    }

    /**
     * Enable or disable Kubernetes schema validation.
     *
     * @param enable Whether to enable validation
     */
    Helm withKubeValidation(boolean enable) {
        this.enableKubeValidation = enable
        return this
    }

    /**
     * Lint a Helm chart for issues and best practices.
     *
     * @param valuesFile Optional values file for linting
     * @param strict Enable strict mode (fail on warnings)
     * @return LintResult object containing lint status and messages
     */
    LintResult lint(String valuesFile = null, boolean strict = false) {
        script.echo "Linting Helm chart in: ${chartDir}"

        StringBuilder cmd = new StringBuilder("helm lint ${chartDir}")

        if (valuesFile && script.fileExists(valuesFile)) {
            cmd.append(" -f ${valuesFile}")
        }

        if (strict) {
            cmd.append(" --strict")
        }

        Integer exitCode = runHelmCommand(cmd.toString())

        LintResult result = new LintResult()
        result.passed = (exitCode == 0)
        result.message = result.passed ? "Chart linting passed" : "Chart linting failed"

        if (!result.passed) {
            script.unstable "Helm lint: ${result.message}"
        } else {
            script.echo "Helm lint: ${result.message}"
        }

        return result
    }

    /**
     * Package a Helm chart into a versioned chart archive.
     *
     * @param destination Directory to store the packaged chart
     * @param chartVersion Override chart version
     * @param appVersion Override app version
     * @return Path to the packaged chart file
     */
    String packageChart(String destination = ".", String chartVersion = null, String appVersion = null) {
        script.echo "Packaging Helm chart from: ${chartDir}"

        script.sh "mkdir -p ${destination}"

        StringBuilder cmd = new StringBuilder("helm package ${chartDir} -d ${destination}")

        if (chartVersion) {
            cmd.append(" --version ${chartVersion}")
        }

        if (appVersion) {
            cmd.append(" --app-version ${appVersion}")
        }

        Integer exitCode = runHelmCommand(cmd.toString())

        if (exitCode != 0) {
            script.error "Failed to package Helm chart"
        }

        // Find the packaged chart
        String chartName = getChartName()
        String version = chartVersion ?: getChartVersion()
        String packagedChart = "${destination}/${chartName}-${version}.tgz"

        script.echo "Chart packaged: ${packagedChart}"
        script.archiveArtifacts artifacts: "${destination}/*.tgz", allowEmptyArchive: true

        return packagedChart
    }

    /**
     * Render Helm templates locally for validation.
     *
     * @param valuesFile Optional values file
     * @param outputDir Directory to write rendered templates
     * @return TemplateResult object
     */
    TemplateResult template(String valuesFile = null, String outputDir = "helm-rendered") {
        script.echo "Rendering Helm templates from: ${chartDir}"

        script.sh "mkdir -p ${outputDir}"

        String release = releaseName ?: "test-release"
        StringBuilder cmd = new StringBuilder("helm template ${release} ${chartDir}")
        cmd.append(" --namespace ${namespace}")
        cmd.append(" --output-dir ${outputDir}")

        if (valuesFile && script.fileExists(valuesFile)) {
            cmd.append(" -f ${valuesFile}")
        }

        if (enableKubeValidation) {
            cmd.append(" --kube-version ${kubernetesVersion}")
        }

        Integer exitCode = runHelmCommand(cmd.toString())

        TemplateResult result = new TemplateResult()
        result.passed = (exitCode == 0)
        result.outputDir = outputDir
        result.message = result.passed ? "Templates rendered successfully" : "Template rendering failed"

        if (!result.passed) {
            script.error "Helm template: ${result.message}"
        } else {
            script.echo "Helm template: ${result.message}"
        }

        return result
    }

    /**
     * Update chart dependencies.
     *
     * @return true if successful
     */
    boolean dependencyUpdate() {
        script.echo "Updating Helm chart dependencies for: ${chartDir}"

        Integer exitCode = runHelmCommand("helm dependency update ${chartDir}")

        if (exitCode != 0) {
            script.error "Failed to update Helm dependencies"
            return false
        }

        script.echo "Helm dependencies updated successfully"
        return true
    }

    /**
     * Build chart dependencies.
     *
     * @return true if successful
     */
    boolean dependencyBuild() {
        script.echo "Building Helm chart dependencies for: ${chartDir}"

        Integer exitCode = runHelmCommand("helm dependency build ${chartDir}")

        if (exitCode != 0) {
            script.error "Failed to build Helm dependencies"
            return false
        }

        script.echo "Helm dependencies built successfully"
        return true
    }

    /**
     * Validate the chart against Kubernetes schemas using helm template --validate.
     *
     * @param valuesFile Optional values file
     * @return ValidationResult object
     */
    ValidationResult validate(String valuesFile = null) {
        script.echo "Validating Helm chart: ${chartDir}"

        String release = releaseName ?: "validation-release"
        StringBuilder cmd = new StringBuilder("helm template ${release} ${chartDir}")
        cmd.append(" --namespace ${namespace}")
        cmd.append(" --validate")
        cmd.append(" --kube-version ${kubernetesVersion}")

        if (valuesFile && script.fileExists(valuesFile)) {
            cmd.append(" -f ${valuesFile}")
        }

        Integer exitCode = runHelmCommand(cmd.toString())

        ValidationResult result = new ValidationResult()
        result.passed = (exitCode == 0)
        result.message = result.passed ? "Chart validation passed" : "Chart validation failed"

        if (!result.passed) {
            script.unstable "Helm validate: ${result.message}"
        } else {
            script.echo "Helm validate: ${result.message}"
        }

        return result
    }

    /**
     * Run full validation suite: lint, template, validate.
     *
     * @param valuesFile Optional values file
     * @param strict Enable strict linting
     * @return true if all checks pass
     */
    boolean validateAll(String valuesFile = null, boolean strict = true) {
        script.echo "Running full Helm chart validation suite"

        boolean allPassed = true

        // Lint
        LintResult lintResult = lint(valuesFile, strict)
        allPassed = allPassed && lintResult.passed

        // Template
        TemplateResult templateResult = template(valuesFile)
        allPassed = allPassed && templateResult.passed

        // Validate
        ValidationResult validationResult = validate(valuesFile)
        allPassed = allPassed && validationResult.passed

        if (allPassed) {
            script.echo "All Helm validations passed"
        } else {
            script.unstable "Some Helm validations failed"
        }

        return allPassed
    }

    /**
     * Get the chart name from Chart.yaml.
     */
    String getChartName() {
        String chartYaml = "${chartDir}/Chart.yaml"
        if (script.fileExists(chartYaml)) {
            return script.sh(script: "grep '^name:' ${chartYaml} | awk '{print \$2}'", returnStdout: true).trim()
        }
        return "unknown"
    }

    /**
     * Get the chart version from Chart.yaml.
     */
    String getChartVersion() {
        String chartYaml = "${chartDir}/Chart.yaml"
        if (script.fileExists(chartYaml)) {
            return script.sh(script: "grep '^version:' ${chartYaml} | awk '{print \$2}'", returnStdout: true).trim()
        }
        return "0.0.0"
    }

    private Integer runHelmCommand(String command) {
        String dockerImage = "${image}:${version}"

        return docker.image(dockerImage)
            .inside("-v ${script.env.WORKSPACE}:/apps -w /apps --entrypoint=''") {
                script.sh(script: command, returnStatus: true)
            }
    }

    /**
     * Result of helm lint operation.
     */
    static class LintResult implements Serializable {
        boolean passed = false
        String message = ""
        List<String> warnings = []
        List<String> errors = []
    }

    /**
     * Result of helm template operation.
     */
    static class TemplateResult implements Serializable {
        boolean passed = false
        String message = ""
        String outputDir = ""
    }

    /**
     * Result of helm validation operation.
     */
    static class ValidationResult implements Serializable {
        boolean passed = false
        String message = ""
        List<String> issues = []
    }
}
