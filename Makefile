gradlew-clean-build:
	./gradlew --no-daemon clean build

docker-build:
	docker build -t localhost:5000/k8s-prometheus-reverse-proxy:latest .

docker-push:
	docker push localhost:5000/k8s-prometheus-reverse-proxy:latest

kubectl-create:
	kubectl apply -f ./manifests -R

kubectl-delete:
	kubectl delete -f ./manifests -R

kubectl-get:
	kubectl get namespace -o wide --all-namespaces
	kubectl get deployment -o wide --all-namespaces
	kubectl get svc -o wide --all-namespaces
	kubectl get pod -o wide --all-namespaces
