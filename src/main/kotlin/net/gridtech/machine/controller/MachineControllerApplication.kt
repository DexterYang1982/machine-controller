package net.gridtech.machine.controller

import net.gridtech.core.util.hostInfoPublisher
import net.gridtech.machine.controller.init.MachineInfoService
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories


@SpringBootApplication
@ComponentScan("net.gridtech")
@EnableMongoRepositories("net.gridtech")
class MachineControllerApplication

fun main(args: Array<String>) {
    runApplication<MachineControllerApplication>(*args)
            .apply {
                val machineInfoService: MachineInfoService = getBean()
                machineInfoService.hostInfo?.apply { hostInfoPublisher.onNext(this) }
            }
}
