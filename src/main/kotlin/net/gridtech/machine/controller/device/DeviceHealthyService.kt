package net.gridtech.machine.controller.device

import net.gridtech.machine.controller.init.BootService
import net.gridtech.machine.controller.utils.ReadWriteService
import net.gridtech.machine.model.entity.Device
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct

@Service
class DeviceHealthyService {
    @Autowired
    lateinit var bootService: BootService
    @Autowired
    lateinit var readWriteService: ReadWriteService

    @PostConstruct
    fun start() {
        bootService.dataHolder.getEntityByConditionObservable { it is Device }.map { it as Device }.subscribe { device ->
            val deviceHealthyField = device.entityClass.healthy.getFieldValue(device)
            val disposable = device.description.observable.switchMap { deviceDefinition ->
                readWriteService.readConditionObservable(deviceDefinition.errorCondition).map {
                    it to device.description.value?.errorCondition
                }
            }.subscribe { (unhealthy, errorCondition) ->
                if (deviceHealthyField.value != !unhealthy) {
                    val session = errorCondition?.let {
                        readWriteService.readConditionFieldValues(it).map { (_, customFieldValue) ->
                            (customFieldValue?.updateTime ?: -1) to (customFieldValue?.session ?: "")
                        }
                    }?.maxBy { it.first }?.second ?: ""

                    deviceHealthyField.update(!unhealthy, session)
                }
            }
            device.onDelete().subscribe { _, _ ->
                disposable.dispose()
            }
        }
    }
}