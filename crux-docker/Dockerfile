FROM clojure:openjdk-11-tools-deps

VOLUME /root/.m2
VOLUME /var/lib/crux

EXPOSE 3000
EXPOSE 7888

WORKDIR /usr/lib/crux

ENTRYPOINT ["clojure", "-J-Dlogback.configurationFile=logback.xml", "-m", "crux.docker"]

ADD logback.xml .
ADD deps.edn .
ADD crux.edn /etc/crux.edn
ADD src/ ./src/