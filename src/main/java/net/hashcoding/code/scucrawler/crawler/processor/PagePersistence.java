package net.hashcoding.code.scucrawler.crawler.processor;

import com.google.gson.Gson;
import net.hashcoding.code.scucrawler.entity.Page;
import net.hashcoding.code.scucrawler.network.Network;
import net.hashcoding.code.scucrawler.network.service.ArticleService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PagePersistence {
    private volatile static PagePersistenceImpl instance;

    private static PagePersistenceImpl getInstance() {
        if (instance == null) synchronized (PagePersistence.class) {
            if (instance == null)
                instance = new PagePersistenceImpl();
        }
        return instance;
    }

    public static int count() {
        return getInstance().count();
    }

    public static void submit(Page page) {
        getInstance().submit(page);
    }

    public static void exit() {
        getInstance().exit();
    }

    public static void exitAsync() {
        getInstance().exitAsync();
    }

    private static class PagePersistenceImpl {
        private AtomicInteger counter = new AtomicInteger(0);
        private AtomicBoolean exit = new AtomicBoolean(false);
        private ThreadPoolExecutor executor =
                new ThreadPoolExecutor(
                        5,
                        9,
                        3,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>());

        private int count() {
            return counter.get();
        }

        private void submit(Page page) {
            counter.incrementAndGet();
            executor.execute(Submitter.create(page));
        }

        private void exit() {
            exitAsync();
            while (!executor.isTerminated()) {
                try {
                    TimeUnit.MICROSECONDS.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        private void exitAsync() {
            exit.set(true);
            executor.shutdown();
        }
    }

    private static class Submitter implements Runnable {
        private static final Logger logger = LoggerFactory.getLogger(Submitter.class);

        private Page page;
        private Gson gson;

        private Submitter(Page page) {
            this.page = page;
            this.gson = new Gson();
        }

        static Submitter create(Page page) {
            return new Submitter(page);
        }

        @Override
        public void run() {
            if (StringUtils.isEmpty(page.thumbnail)) {
                page.thumbnail = "";
            }
            if (StringUtils.isEmpty(page.content)) {
                page.content = "<p>No content here.</p>";
            }

            send(page);
            // writeToFile(page);
        }

        private void send(Page page) {
            String createdAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(page.createdAt);
            ArticleService service = Network.getArticleService();
            service.put(page.type, page.url, page.title, page.thumbnail, page.content, createdAt)
                    .flatMap(result -> {
                        if (result.getCode() != 200) {
                            throw new RuntimeException(result.getDetailMessage());
                        }
                        return service.updateAttachments(result.getData(), page.attachments);
                    })
                    .subscribe(stringResult -> {
                            },
                            throwable -> logger.error("save article "
                                    + page.url + "failed", throwable));
        }

        private void writeToFile(Page page) {
            try {
                OutputStream stream = new FileOutputStream("D:\\webmagic\\" + page.title + ".html");
                OutputStreamWriter writer = new OutputStreamWriter(stream);
                writer.write(page.content);
                writer.flush();
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
