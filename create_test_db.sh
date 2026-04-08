podman run -d \
  --name ebazi-db-test \
  -e POSTGRES_USER=user \
  -e POSTGRES_PASSWORD=wasans \
  -e POSTGRES_DB=main \
  -p 5432:5432 \
  -v testdata:/var/lib/postgresql \
  postgres:latest