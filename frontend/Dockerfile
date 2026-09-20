# DataBuddy 前端镜像:纯静态页 + nginx 反代(零构建;同源访问 API)
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html style.css /usr/share/nginx/html/
COPY js /usr/share/nginx/html/js
