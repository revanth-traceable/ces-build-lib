package com.cloudogu.ces.cesbuildlib

def call(Map config) {
    def cesBuildLib = config.cesBuildLib
    def sonarServer = config.sonarServer ?: 'ces-sonar'
    def jdkImage = config.jdkImage ?: 'eclipse-temurin:17.0.14_7-jdk-alpine'
    def githubToken = config.githubToken
    
    if (!cesBuildLib) {
        error("cesBuildLib is required")
    }
    
    def sonarQube = cesBuildLib.SonarQube.new(this, sonarServer)
    
    if (isPullRequest() && githubToken) {
        echo "Updating PR analysis results to GitHub..."
        sonarQube.updateAnalysisResultOfPullRequestsToGitHub(githubToken)
    }
    
    def mvnWithJdk17 = cesBuildLib.MavenWrapperInDocker.new(this, jdkImage)
    mvnWithJdk17.useLocalRepoFromJenkins = true
    
    echo "Running SonarQube analysis..."
    sonarQube.analyzeWith(mvnWithJdk17)
    
    def qualityGatePassed = sonarQube.waitForQualityGateWebhookToBeCalled()
    
    if (!qualityGatePassed) {
        unstable("Pipeline unstable due to SonarQube quality gate failure")
    }
    
    return qualityGatePassed
}
