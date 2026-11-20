FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/raahat-control-plane .

FROM alpine:3.21
RUN addgroup -S raahat && adduser -S -G raahat raahat
WORKDIR /app
COPY --from=build /out/raahat-control-plane /usr/local/bin/raahat-control-plane
RUN mkdir -p /app/data && chown -R raahat:raahat /app
USER raahat
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/raahat-control-plane"]
