package net.gridtech.machine.controller.device

import io.reactivex.Observable
import net.gridtech.core.util.cast
import net.gridtech.machine.controller.init.BootService
import net.gridtech.machine.model.entity.Device
import net.gridtech.machine.model.entityField.ProcessState
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct

@Service
class DeviceProcessQueueService {
    @Autowired
    lateinit var bootService: BootService

    @PostConstruct
    fun start() {
        bootService.dataHolder.getEntityByConditionObservable { it is Device }.map { cast<Device>(it)!! }.subscribe { device ->
            val currentProcess = device.entityClass.currentProcess.getFieldValue(device)
            val processQueue = device.entityClass.processQueue.getFieldValue(device)
            val disposable = Observable.merge(
                    processQueue.observable.filter { queue ->
                        currentProcess.value?.state == ProcessState.FINISHED && queue.isNotEmpty()
                    },
                    currentProcess.observable.filter { processRuntime ->
                        processRuntime.state == ProcessState.FINISHED && processQueue.value?.isNotEmpty() == true
                    }.map {
                        processQueue.value!!
                    }
            ).subscribe {
                val queue = it.toMutableList()
                val nextProcess = queue.first()
                queue.removeAt(0)
                currentProcess.update(nextProcess, nextProcess.session())
                processQueue.update(this, nextProcess.session())
            }
            device.onDelete().subscribe { _, _ ->
                disposable.dispose()
            }
        }
    }
}