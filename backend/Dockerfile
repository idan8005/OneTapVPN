FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/raahat-api ./cmd/api

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/raahat-api /raahat-api
USER nonroot:nonroot
EXPOSE 8080
ENTRYPOINT ["/raahat-api"]
