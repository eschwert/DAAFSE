package ru.ifmo.ailab.daafse.alertservice.services;

import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.deri.cqels.engine.RDFStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ifmo.ailab.daafse.alertservice.CQELSEngine;
import ru.ifmo.ailab.daafse.alertservice.StreamReaderService;
import ru.ifmo.ailab.daafse.alertservice.StreamURI;

@Singleton
public class StreamReaderServiceImpl implements StreamReaderService {

    private static final Logger logger = LoggerFactory.getLogger(
            StreamReaderServiceImpl.class);
    private static final String EXCHANGE_TYPE = "topic";
    private Map<URI, ConnectionFactory> factories;
    private Map<URI, Connection> connections;
    private Map<StreamURI, Channel> channels;

    @Inject
    private CQELSEngine cqelsEngine;

    @PostConstruct
    public void postConstruct() {
        try {
            factories = new HashMap<>();
            connections = new HashMap<>();
            channels = new HashMap<>();
            logger.debug("service has been initialized");
        } catch (Exception ex) {
            logger.warn(ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void preDestroy() {
        channels.forEach((k, v) -> {
            try {
                if (v.isOpen()) {
                    v.close();
                } else {
                    v.abort();
                }
            } catch (IOException ex) {
                logger.debug(ex.getMessage(), ex);
            }
        });
        connections.forEach((k, v) -> {
            try {
                if (v.isOpen()) {
                    v.close();
                } else {
                    v.abort();
                }
            } catch (IOException ex) {
                logger.debug(ex.getMessage(), ex);
            }
        });
        logger.debug("service has been destroyed");
    }

    @Override
    public void startReadStream(final StreamURI uri) {
        if (channels.containsKey(uri) && channels.get(uri).isOpen()) {
            logger.debug("Stream [{}] is already being read!", uri);
            return;
        }
        ConnectionFactory cf;
        Connection c;
        try {
            if (!factories.containsKey(uri.getServerURI())) {
                cf = new ConnectionFactory();
                cf.setUri(uri.getServerURI());
                factories.put(uri.getServerURI(), cf);

                c = cf.newConnection();
                connections.put(uri.getServerURI(), c);
            } else {
                cf = factories.get(uri.getServerURI());
                if (!connections.containsKey(uri.getServerURI())) {
                    c = cf.newConnection();
                    connections.put(uri.getServerURI(), c);
                } else {
                    c = connections.get(uri.getServerURI());
                }
            }
            final Channel channel = c.createChannel();
            channel.exchangeDeclare(uri.getExchangeName(), EXCHANGE_TYPE);
            final String queueName = channel.queueDeclare().getQueue();
            channel.queueBind(queueName, uri.getExchangeName(),
                    uri.getRoutingKey());
            channel.basicConsume(queueName,
                    new ObservationConsumer(channel, uri));
            channels.put(uri, channel);
            logger.debug("Started reading [{}] stream", uri);
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
    }

    @Override
    public void stopReadStream(final StreamURI uri) {
        if (channels.containsKey(uri)) {
            try {
                final Channel c = channels.get(uri);
                if (c.isOpen()) {
                    c.close();
                } else {
                    c.abort();
                }
                logger.debug("Stopped reading [{}] stream", uri);
            } catch (IOException ex) {
                logger.warn(ex.getMessage(), ex);
            }
        } else {
            logger.debug("Stream [{}] is not being read!", uri);
        }
    }

    private class ObservationConsumer extends DefaultConsumer {

        private final RDFStream stream;
        private final StreamURI uri;

        public ObservationConsumer(final Channel channel, final StreamURI uri) {
            super(channel);
            this.uri = uri;
            this.stream = new RDFStream(cqelsEngine.getContext(),
                    uri.toString()) {

                        @Override
                        public void stop() {
                            logger.debug("RDFStream.stop() has been called!");
                        }
                    };
        }

        @Override
        public void handleDelivery(String consumerTag, Envelope envelope,
                AMQP.BasicProperties properties, byte[] body)
                throws IOException {
            Model model = ModelFactory.createDefaultModel().read(
                    new StringReader(new String(body)), null);
            StmtIterator i = model.listStatements();
            while (i.hasNext()) {
                Triple triple = i.nextStatement().asTriple();
//                logger.debug("{}", triple);
                stream.stream(triple);
            }
        }

    }

}
