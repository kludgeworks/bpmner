package dev.groknull.bpmner

import com.embabel.agent.config.annotation.EnableAgents
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAgents
class BpmnerApplication

fun main(args: Array<String>) {
    System.getenv("BUILD_WORKING_DIRECTORY")?.let {
        System.setProperty("user.dir", it)
    }
    runApplication<BpmnerApplication>(*args)
}
