FROM alpine:3.19

RUN apk add --no-cache curl jq bash

COPY setup.sh /setup.sh
RUN chmod +x /setup.sh

CMD ["/setup.sh"]
