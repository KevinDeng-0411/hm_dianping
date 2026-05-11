FROM nginx:alpine
COPY docker/nginx/nginx.conf /etc/nginx/nginx.conf
COPY frontend/html /usr/share/nginx/html
RUN chmod -R a+r /usr/share/nginx/html
