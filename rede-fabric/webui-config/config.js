// Sobrescreve web-ui/config.js quando a UI roda via docker-compose (profile
// "app") — montado como volume read-only em /usr/share/nginx/html/config.js
// dentro do container `web-ui` (ver docker-compose.yaml, servico `web-ui`).
//
// Aponta para a porta EXTERNA do host onde a api-gateway esta publicada
// (ver comentario de porta em docker-compose.yaml, servico `api-gateway`),
// pois o browser do usuario acessa a API via localhost do HOST, nao via
// nome de servico da rede interna do Docker.
window.APP_CONFIG = {
  apiBaseUrl: "http://localhost:8082/api/v1"
};
