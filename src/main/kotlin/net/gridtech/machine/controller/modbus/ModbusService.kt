package net.gridtech.machine.controller.modbus


import net.gridtech.machine.controller.init.BootService
import net.gridtech.machine.model.entity.ModbusSlave
import net.gridtech.machine.model.entity.ModbusUnit
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.PostConstruct


@Service
class ModbusService {
    @Autowired
    lateinit var bootService: BootService
    private val slaveTaskMap = ConcurrentHashMap<String, ModbusSlaveTask>()


    @PostConstruct
    fun start() {
        bootService.dataHolder.getEntityByConditionObservable { it is ModbusSlave }.map { it as ModbusSlave }.subscribe { modbusSlave ->
            slaveTaskMap[modbusSlave.id] =
                    ModbusSlaveTask(
                            modbusSlave,
                            bootService.dataHolder.getEntityByConditionObservable { it is ModbusUnit && it.source?.path?.contains(modbusSlave.id) == true }
                                    .map { it as ModbusUnit }
                    )
            modbusSlave.onDelete().subscribe { _, _ ->
                slaveTaskMap.remove(modbusSlave.id)?.stop()
            }
        }
    }
}