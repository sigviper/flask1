pipeline {
	agent any
	
    environment {
		APP_IMAGE = "677143160410.dkr.ecr.eu-central-1.amazonaws.com/cloud-demo/app-flask1"
		APP_PORT = 5000
	}
    
	stages {
		stage('Preparation') {
			environment {
				git_commit_short = "None"
			}

			steps {
				// Get code from a GitHub repository
				git 'https://github.com/fastviper/flask1.git'
			
			
				// set docker image tag to short SHA1 of commit from which we build
				// this is nice for traceability, but also Amazon EKS fails to refresh images with :latest tag
				script {
					git_commit_short = sh(returnStdout: true, script: "git rev-parse --short HEAD").trim()
					APP_IMAGE = sh(returnStdout: true, script: "echo $APP_IMAGE:$git_commit_short").trim()
				}
			}
		}
	   
		stage('Build') {
			steps {
				echo 'In build'
				echo APP_IMAGE
				
				// docker build
				sh "docker build --build-arg git_commit_short=$git_commit_short -t $APP_IMAGE ."

				// docker registry login
				sh '$(aws ecr get-login --region eu-central-1 --no-include-email)'
				// docker push (not needed locally, but eventually...)
				sh "docker push $APP_IMAGE"
				
				
			}
		}
		stage('CI-start') {
			steps {
				echo 'In CI-start'
				echo APP_IMAGE

				echo 'Deploying to local CI env'
				// APP_IMAGE and APP_PORT will be taken automatically by docker-compose as documented in
				// https://docs.docker.com/compose/compose-file/#variable-substitution
				sh "APP_IMAGE=$APP_IMAGE /usr/local/bin/docker-compose up -d "
			}
		}
		stage('CI-Test') {
			steps {
				echo 'Testing local CI env'
				// -f : fail on http code 4xx or 5xx
				// -s : curl is silent
				// -o /dev/null : send any output to hell (just be aware that Lucifer has good taste)
				sh 'curl -fs -o /dev/null http://localhost:$APP_PORT'
			}
		}
		// this will not work without setting security key for Jenkins
		stage('Deploy') {
			environment {
				// The switch of IAM users is done here, because jenkis IAM cannot push to ECR - TODO!!
			
				// see https://jenkins.io/doc/book/pipeline/jenkinsfile/#handling-credentials
				AWS_ACCESS_KEY_ID     = credentials('jenkins-aws-secret-key-id')
				AWS_SECRET_ACCESS_KEY = credentials('jenkins-aws-secret-access-key')
			}
			 
		
			steps {
				// first we need the credentials to operate on cluster
				// this is already set up, and it's done once - by mapping AWS IAM role to kubernetes RBAC
				// we only need variables, as in environment above
				// REMEMBER TO GIVE jenkins RIGHTS TO DO DEPLOYMENTS -- give_jenkins_rights_on_cluster.sh
				//
				// Please, pretty please - check ~jenkins/.kube/config - this file contains information on:
				// - cluster id
				// - connection between AWS IAM and kubernetes RBAC (users: section) - only then it will work
				// - no file = kubectl not works, copy from user that created cluster with eksctl
				//
				// let's verify it works, ok? :)
				sh "aws sts get-caller-identity"
				sh "/usr/local/bin/kubectl get pods -l app=flask1"
			
				echo 'Deploy to kubernetes cluster - complete CD (note that for this LoadBalancer must already be setup!)'
				sh "curl -o /var/lib/jenkins/tmp/jenkins-flask1-deployment.yaml.tmpl https://raw.githubusercontent.com/fastviper/flask1/master/jenkins-pipeline/flask1-deployment.yaml "
				sh "cat /var/lib/jenkins/tmp/jenkins-flask1-deployment.yaml.tmpl | sed -e \"s!SED_FOR_TAGGED_IMAGE!$APP_IMAGE!\" -e 's/THIS_STRING_IS_REPLACED_EVERY_RUN/$BUILD_ID/' > /var/lib/jenkins/tmp/jenkins-flask1-deployment.yaml"
				sh "/usr/local/bin/kubectl apply -f /var/lib/jenkins/tmp/jenkins-flask1-deployment.yaml"
			}
		}
		stage('CICD-Test') {
			steps {
				// remember to setup load balancer and wait for DNS propagation, 15s won't be enough initially -- flask1-load-balancer-setup.sh
				echo 'Testing app availablility via Load Balancer'
				sh 'sleep 15'
				sh 'curl -fs -o /dev/null a9f9ad300fae211e9ac8c0621de89f28-729101062.eu-central-1.elb.amazonaws.com:8888'
			}
		}

	}
}