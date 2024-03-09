gcloud --quiet services enable pubsub.googleapis.com
gcloud --quiet services enable container.googleapis.com
gcloud --quiet config set compute/region europe-west2
gcloud --quiet config set compute/zone europe-west2-a
gcloud --quiet container clusters create demo-cluster --num-nodes=4 --max-nodes=8 --enable-autoscaling --machine-type=e2-medium
gcloud container clusters get-credentials demo-cluster