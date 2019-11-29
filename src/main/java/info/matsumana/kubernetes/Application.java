package info.matsumana.kubernetes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
@Slf4j
public class Application {

    public static void main(String[] args) throws Exception {
        Runtime.getRuntime()
               .addShutdownHook(new Thread(() -> log.info("Start shutting down")));

        SpringApplication.run(Application.class, args);
    }
}
