helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add stable https://kubernetes-charts.storage.googleapis.com/
helm repo update
#
kubectl create namespace fake-load
kubectl config set-context --current --namespace fake-load
helm upgrade --install prometheus prometheus-community/prometheus
#
kubectl expose deployment prometheus-server --target-port 9090 --port 8080 --type LoadBalancer --name ext-prometheus-server
#