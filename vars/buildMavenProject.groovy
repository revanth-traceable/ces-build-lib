package com.cloudogu.ces.cesbuildlib

def call(Map config = [:]) {
    def cesBuildLib = config.cesBuildLib
    def jdkImage = config.jdkImage ?: 'eclipse-temurin:11.0.25_9-jdk-alpine'
    def skipTests = config.skipTests ?: false
    def generateCoverage = config.generateCoverage ?: true
    
    if (!cesBuildLib) {
        error("cesBuildLib is required")
    }
    
    def mvn = cesBuildLib.MavenWrapperInDocker.new(this, jdkImage)
    mvn.useLocalRepoFromJenkins = true
    
    stage('Compile') {
        echo "Compiling project..."
        mvn 'clean compile -DskipTests'
    }
    
    stage('Unit Test') {
        if (skipTests) {
            echo "Skipping tests as requested"
        } else {
            echo "Running unit tests..."
            mvn 'test'
            junit allowEmptyResults: true, 
                  testResults: '**/target/surefire-reports/TEST-*.xml,**/target/failsafe-reports/TEST-*.xml'
        }
    }
    
    stage('Package') {
        echo "Packaging artifacts..."
        mvn 'package -DskipTests'
        archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
    }
    
    if (generateCoverage) {
        stage('Coverage Report') {
            echo "Generating code coverage report..."
            mvn 'org.jacoco:jacoco-maven-plugin:0.8.12:report'
            
            publishHTML([
                allowMissing: true,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: 'target/site/jacoco',
                reportFiles: 'index.html',
                reportName: 'Code Coverage Report'
            ])
        }
    }
    
    return mvn
}
