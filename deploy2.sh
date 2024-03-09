#
kubectl delete deploy -l app=fake-load-1
kubectl delete deploy -l app=fake-load-2
kubectl delete deploy -l app=fake-load-3
kubectl delete deploy -l app=k8-metrics
kubectl delete role --all
kubectl delete rolebinding --all
#
kubectl create deployment fake-load-1 --image registry.hub.docker.com/gregclinker/fake-load:0.1 --replicas 0
kubectl set resources deployment fake-load-1 --requests cpu=250m,memory=256Mi --limits cpu=500m,memory=512Mi
kubectl set env deploy fake-load-1 --env RANDOM_STRING_LENGTH=10 --env THREADS_TO_RUN=2
kubectl scale deployment fake-load-1 --replicas 1
#
kubectl create deployment fake-load-2 --image registry.hub.docker.com/gregclinker/fake-load:0.1 --replicas 0
kubectl set resources deployment fake-load-2 --requests cpu=250m,memory=256Mi --limits cpu=500m,memory=512Mi
kubectl set env deploy fake-load-2 --env RANDOM_STRING_LENGTH=20 --env THREADS_TO_RUN=4
kubectl scale deployment fake-load-2 --replicas 1
#
kubectl create deployment fake-load-3 --image registry.hub.docker.com/gregclinker/fake-load:0.1 --replicas 0
kubectl set resources deployment fake-load-3 --requests cpu=250m,memory=256Mi --limits cpu=500m,memory=512Mi
kubectl set env deploy fake-load-3 --env RANDOM_STRING_LENGTH=40 --env THREADS_TO_RUN=6
kubectl scale deployment fake-load-3 --replicas 1
