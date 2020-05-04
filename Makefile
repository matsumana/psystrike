APP_VERSION := $(shell grep '^version=' gradle.properties | perl -pe "s/version=//g")

.PHONY: all

gradlew-clean-build:
	./gradlew --no-daemon clean build && cd ./psystrike/build/libs && jar xvf *-$(APP_VERSION).jar

docker-build-local: gradlew-clean-build
	docker build -t localhost:5000/psystrike:latest .

docker-build-hub: gradlew-clean-build
	docker build -t matsumana/psystrike:$(APP_VERSION) .

docker-push-local: docker-build-local
	docker push localhost:5000/psystrike:latest

docker-push-hub: docker-build-hub
	docker push matsumana/psystrike:$(APP_VERSION)

kubectl-create-example:
	kubectl apply -f ./example/manifests -R

kubectl-delete-example:
	kubectl delete -f ./example/manifests -R

kubectl-get:
	kubectl get deployment -o wide
	kubectl get svc -o wide
	kubectl get pod -o wide
