package net.gridtech.machine.controller.utils

import io.reactivex.Observable
import net.gridtech.core.util.cast
import net.gridtech.machine.controller.init.BootService
import net.gridtech.machine.model.ReadCondition
import net.gridtech.machine.model.ReadTargetType
import net.gridtech.machine.model.entity.Device
import net.gridtech.machine.model.entity.ModbusUnit
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class ReadConditionService {
    @Autowired
    lateinit var bootService: BootService

    fun readConditionValue(readCondition: ReadCondition): Boolean =
            readCondition.reads.map { entityRead ->
                val customFieldValue = when (entityRead.targetType) {
                    ReadTargetType.DEVICE_STATUS -> {
                        bootService.dataHolder.entityHolder[entityRead.entityId]?.takeIf { it is Device }?.let { device ->
                            cast<Device>(device)!!.description.value?.status?.find { status -> status.id == entityRead.targetId }
                                    ?.let { modbusRead ->
                                        bootService.dataHolder.entityHolder[entityRead.entityId]?.takeIf { it is ModbusUnit }?.let { modbusUnit ->
                                            cast<ModbusUnit>(modbusUnit)!!.entityClass.description.value?.read?.find { readPoint -> readPoint.id == modbusRead.readPointId }
                                                    ?.let { readPoint ->
                                                        modbusUnit.getCustomFieldValue(readPoint.resultFieldId)
                                                    }
                                        }
                                    }
                        }
                    }
                    ReadTargetType.CUSTOM_OUTPUT -> {
                        bootService.dataHolder.entityHolder[entityRead.entityId]?.getCustomFieldValue(entityRead.targetId)
                    }
                }
                customFieldValue?.value?.id == entityRead.valueDescriptionId
            }.let {
                if (readCondition.matchAll)
                    !it.contains(false)
                else
                    it.isEmpty() || it.contains(true)
            }

    fun readConditionObservable(readCondition: ReadCondition): Observable<Boolean> {
        val watchEachFieldValue = readCondition.reads.map { entityRead ->
            when (entityRead.targetType) {
                ReadTargetType.DEVICE_STATUS -> {
                    bootService.dataHolder.getEntityByIdObservable<Device>(entityRead.entityId).toObservable()
                            .flatMap { device ->
                                device.description.observable.switchMap { deviceDefinition ->
                                    deviceDefinition.status.find { status -> status.id == entityRead.targetId }?.let { modbusRead ->
                                        bootService.dataHolder.getEntityByIdObservable<ModbusUnit>(modbusRead.modbusUnitId).toObservable()
                                                .flatMap { modbusUnit ->
                                                    modbusUnit.entityClass.description.observable
                                                            .switchMap { modbusUnitDescription ->
                                                                modbusUnitDescription.read.find { readPoint -> readPoint.id == modbusRead.readPointId }?.let { readPoint ->
                                                                    modbusUnit.getCustomFieldValue(readPoint.resultFieldId)?.observable?.map { it.id == entityRead.valueDescriptionId }
                                                                } ?: Observable.empty()
                                                            }
                                                }
                                    } ?: Observable.empty()
                                }
                            }

                }
                ReadTargetType.CUSTOM_OUTPUT -> {
                    bootService.dataHolder.entityHolder[entityRead.entityId]?.getCustomFieldValue(entityRead.targetId)?.observable?.map { it.id == entityRead.valueDescriptionId }
                            ?: Observable.empty()
                }
            }
        }
        return Observable.combineLatest(watchEachFieldValue) {
            if (readCondition.matchAll)
                !it.contains(false)
            else
                it.isEmpty() || it.contains(true)
        }.distinctUntilChanged()
    }

}