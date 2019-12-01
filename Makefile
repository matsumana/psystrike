gradlew-clean-build:
	./gradlew --no-daemon clean build

docker-build:
	docker build -t localhost:5000/psystrike:latest .

docker-push:
	docker push localhost:5000/psystrike:latest

kubectl-create-example:
	kubectl apply -f ./example/manifests -R

kubectl-delete-example:
	kubectl delete -f ./example/manifests -R

kubectl-get:
	kubectl get namespace -o wide --all-namespaces
	kubectl get deployment -o wide --all-namespaces
	kubectl get svc -o wide --all-namespaces
	kubectl get pod -o wide --all-namespaces
