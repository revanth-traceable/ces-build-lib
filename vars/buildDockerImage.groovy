package com.cloudogu.ces.cesbuildlib

def call(Map config) {
    def docker = new Docker(this)
    
    def imageName = config.imageName ?: 'app'
    def imageTag = config.imageTag ?: 'latest'
    def buildContext = config.buildContext ?: '.'
    def additionalTags = config.additionalTags ?: []
    def registryUrl = config.registryUrl
    def registryCredentials = config.registryCredentials
    def pushImage = config.pushImage ?: false
    
    echo "Building Docker image: ${imageName}:${imageTag}"
    echo "Build context: ${buildContext}"
    
    def dockerImage = docker.build("${imageName}:${imageTag}", buildContext)
    
    echo "Docker image built successfully: ${imageName}:${imageTag}"
    
    additionalTags.each { tag ->
        echo "Tagging image as: ${imageName}:${tag}"
        dockerImage.tag("${imageName}:${tag}")
    }
    
    if (pushImage && registryUrl && registryCredentials) {
        echo "Pushing Docker image to registry: ${registryUrl}"
        docker.withRegistry(registryUrl, registryCredentials) {
            dockerImage.push()
            additionalTags.each { tag ->
                echo "Pushing tag: ${imageName}:${tag}"
                dockerImage.push(tag)
            }
        }
        echo "Docker image pushed successfully"
    }
    
    return dockerImage
}
