package com.cloudogu.ces.cesbuildlib

def call(Map config) {
    def trivy = new Trivy(this)
    
    def imageName = config.imageName
    def imageTag = config.imageTag
    def severityLevels = config.severityLevels ?: ['CRITICAL', 'HIGH']
    def strategy = config.strategy ?: TrivyScanStrategy.UNSTABLE
    def trivyVersion = config.trivyVersion ?: '0.57.1'
    
    if (!imageName || !imageTag) {
        error("imageName and imageTag are required parameters")
    }
    
    def fullImageName = "${imageName}:${imageTag}"
    def allScansClean = true
    
    severityLevels.each { severity ->
        def severityLabel = severity.toString().toLowerCase()
        def reportFile = "trivy/trivy-${severityLabel}-report.json"
        def htmlReportFile = "trivy/trivy-${severityLabel}-report.html"
        
        echo "Scanning Docker image for ${severity} vulnerabilities..."
        echo "Image: ${fullImageName}"
        
        def scanResult = trivy.scanImage(
            fullImageName,
            severity,
            strategy,
            "--ignore-unfixed --db-repository public.ecr.aws/aquasecurity/trivy-db --java-db-repository public.ecr.aws/aquasecurity/trivy-java-db",
            reportFile
        )
        
        if (scanResult) {
            echo "No ${severity} vulnerabilities found"
        } else {
            echo "WARNING: ${severity} vulnerabilities detected - check report"
            allScansClean = false
        }
        
        trivy.saveFormattedTrivyReport(
            reportFile,
            TrivyScanFormat.HTML,
            htmlReportFile
        )
        
        publishHTML([
            allowMissing: true,
            alwaysLinkToLastBuild: true,
            keepAll: true,
            reportDir: 'trivy',
            reportFiles: "trivy-${severityLabel}-report.html",
            reportName: "Trivy Security Scan - ${severity}"
        ])
    }
    
    return allScansClean
}
