package net.gridtech.machine.controller.device

import net.gridtech.machine.controller.init.BootService
import net.gridtech.machine.controller.utils.ReadConditionService
import net.gridtech.machine.model.entity.Device
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct

@Service
class DeviceHealthyService {
    @Autowired
    lateinit var bootService: BootService
    @Autowired
    lateinit var readConditionService: ReadConditionService

    @PostConstruct
    fun start() {
        bootService.dataHolder.getEntityByConditionObservable { it is Device }.map { it as Device }.subscribe { device ->
            val deviceHealthyField = device.entityClass.healthy.getFieldValue(device)
            val disposable = device.description.observable.switchMap { deviceDefinition ->
                readConditionService.readConditionObservable(deviceDefinition.errorCondition)
            }.subscribe { unhealthy ->
                if (deviceHealthyField.value != !unhealthy) {
                    deviceHealthyField.update(!unhealthy)
                }
            }
            device.onDelete().subscribe { _, _ ->
                disposable.dispose()
            }
        }
    }
}