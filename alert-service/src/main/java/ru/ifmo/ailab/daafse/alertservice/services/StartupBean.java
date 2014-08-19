package ru.ifmo.ailab.daafse.alertservice.services;

import com.hp.hpl.jena.query.ResultSet;
import com.rabbitmq.client.Channel;
import java.net.URISyntaxException;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.inject.Inject;
import org.aeonbits.owner.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ifmo.ailab.daafse.alertservice.SPARQLRemoteService;
import ru.ifmo.ailab.daafse.alertservice.StreamService;
import ru.ifmo.ailab.daafse.alertservice.StreamURI;
import ru.ifmo.ailab.daafse.alertservice.config.ServiceConfig;

@Startup
@Singleton
public class StartupBean {

    private static final Logger logger = LoggerFactory.getLogger(
            StartupBean.class);
    private static final ServiceConfig CONFIG = ConfigFactory.create(
            ServiceConfig.class);

    @Inject
    private SPARQLRemoteService sparqlRs;
    @Inject
    private StreamService streamRs;

    @PostConstruct
    void init() {
        logger.debug("initializing...");
        try {
            Channel channel = streamRs.getOrCreateChannel(
                    new StreamURI(CONFIG.alertsStreamURI()));
            logger.debug("{}", channel.isOpen());
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
        ResultSet results = sparqlRs.select("PREFIX em:<http://purl.org/NET/ssnext/electricmeters#>"
                + "SELECT ?streamUri {"
                + "	GRAPH <http://192.168.134.114/SmartMetersDB/> {"
                + "    	?x em:hasStream ?streamUri ."
                + "    }"
                + "}");
        while (results.hasNext()) {
            String uri = results.nextSolution().getResource("streamUri").getURI();
            try {
                streamRs.register(new StreamURI(uri));
            } catch (URISyntaxException ex) {
                logger.warn(ex.getMessage(), ex);
            }
        }
        logger.debug("initialized.");
    }

    @PreDestroy
    public void destroy() {
        logger.debug("destroyed.");
    }

}
