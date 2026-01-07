package com.cloudogu.ces.cesbuildlib

def call(Map config) {
    def docker = new Docker(this)
    
    def imageName = config.imageName ?: 'app'
    def imageTag = config.imageTag ?: 'latest'
    def buildContext = config.buildContext ?: '.'
    def additionalTags = config.additionalTags ?: []
    
    echo "Building Docker image: ${imageName}:${imageTag}"
    echo "Build context: ${buildContext}"
    
    def dockerImage = docker.build("${imageName}:${imageTag}", buildContext)
    
    echo "Docker image built successfully"
    echo "Image ID: ${dockerImage.getId()}"
    
    additionalTags.each { tag ->
        echo "Tagging image as: ${imageName}:${tag}"
        dockerImage.tag("${imageName}:${tag}")
    }
    
    return dockerImage
}
