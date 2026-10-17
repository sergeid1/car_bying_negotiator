proxy_pass http://127.0.0.1:8080;
proxy_http_version 1.1;
proxy_set_header Host otdog.com;
# Only verified requests reach here; the service checks the secret again before trusting the visitor address.
proxy_set_header X-Origin-Verify $http_x_origin_verify;
proxy_set_header X-Otdog-Client-Address $otdog_client_address;
proxy_set_header Forwarded "";
proxy_set_header X-Forwarded-For "";
proxy_set_header X-Forwarded-Host "";
proxy_set_header X-Forwarded-Proto https;
proxy_set_header Connection "";
proxy_request_buffering off;
proxy_buffering off;
proxy_cache off;
proxy_hide_header Cache-Control;
proxy_read_timeout 60s;
