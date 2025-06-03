FROM rabbitmq:3.13-management-alpine

RUN apk add --no-cache curl bash

COPY helpers/collect_metrics.sh /usr/local/bin/collect_metrics.sh
RUN chmod +x /usr/local/bin/collect_metrics.sh

COPY c02/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["rabbitmq-server"]
