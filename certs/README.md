# certs (로컬 전용 — 커밋 금지)

`docker compose --profile roles-anywhere up` 으로 로컬 컨테이너를 S3 스토리지로 띄울 때
`roles-anywhere-helper` 가 읽는 클라이언트 인증서를 두는 디렉터리입니다.

## 필요한 파일

- `client-cert.pem` — IAM Roles Anywhere Trust Anchor 에 등록한 CA 가 발급한 클라이언트 인증서
- `client-key.pem` — 위 인증서의 개인키

## 인증서 요구사항

불충족 시 `AccessDeniedException: ... Insufficient certificate` 로 거부됩니다.

- `keyUsage = critical, digitalSignature` (end-entity 인증서 필수)
- `basicConstraints = critical, CA:FALSE`
- Trust Anchor 에 등록된 CA 가 서명한 인증서일 것

발급 예시:

```bash
cat > client-ext.cnf <<'EOF'
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid
EOF

openssl genrsa -out client-key.pem 2048
openssl req -new -key client-key.pem -subj "/CN=<client-cn>" -out client.csr
openssl x509 -req -in client.csr -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial \
  -days 365 -sha256 -extfile client-ext.cnf -out client-cert.pem
```

이 디렉터리의 PEM 파일은 `.gitignore` 로 제외됩니다 (개인키 커밋 방지).
