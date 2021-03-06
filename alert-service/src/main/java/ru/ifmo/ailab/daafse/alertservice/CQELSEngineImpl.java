package ru.ifmo.ailab.daafse.alertservice;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Singleton;
import org.aeonbits.owner.ConfigFactory;
import org.deri.cqels.engine.ExecContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ifmo.ailab.daafse.alertservice.config.ServiceConfig;

@Singleton
public class CQELSEngineImpl implements CQELSEngine {

    private static final Logger logger = LoggerFactory.getLogger(
            CQELSEngineImpl.class);
    private static final ServiceConfig CONFIG = ConfigFactory.create(
            ServiceConfig.class);
    private static final File HOME = new File(CONFIG.cqelsHome());
    private static ExecContext context;

    @PostConstruct
    public void postConstruct() {
        if (!HOME.exists()) {
            HOME.mkdir();
            HOME.setWritable(true);
            logger.debug("CQELS HOME: " + HOME.getAbsolutePath());
        }
        context = new ExecContext(CONFIG.cqelsHome(), true);
    }

    @PreDestroy
    public void preDestroy() {
        context.env().close();
        context.getDataset().close();
        context.getARQExCtx().getDataset().close();
        context.dictionary().close();
        if (HOME.exists()) {
            try {
                Files.walkFileTree(HOME.toPath(), new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }

                });
            } catch (IOException ex) {
                logger.error(ex.getMessage(), ex);
            }
        }
    }

    @Override
    public ExecContext getContext() {
        synchronized (CQELSEngineImpl.class) {
            return context;
        }
    }

}
