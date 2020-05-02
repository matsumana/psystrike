IMAGE_TAG := $(shell grep 'version =' build.gradle | awk '{print $$3}' | perl -pe "s/'//g")

.PHONY: all

gradlew-clean-build:
	./gradlew --no-daemon clean build && cd ./build/libs && jar xvf psystrike-*.jar

docker-build-local: gradlew-clean-build
	docker build -t localhost:5000/psystrike:latest .

docker-build-hub: gradlew-clean-build
	docker build -t matsumana/psystrike:$(IMAGE_TAG) .

docker-push-local: docker-build-local
	docker push localhost:5000/psystrike:latest

docker-push-hub: docker-build-hub
	docker push matsumana/psystrike:$(IMAGE_TAG)

kubectl-create-example:
	kubectl apply -f ./example/manifests -R

kubectl-delete-example:
	kubectl delete -f ./example/manifests -R

kubectl-get:
	kubectl get deployment -o wide
	kubectl get svc -o wide
	kubectl get pod -o wide
