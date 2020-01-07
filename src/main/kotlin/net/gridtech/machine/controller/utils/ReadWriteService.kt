package net.gridtech.machine.controller.utils

import io.reactivex.Observable
import net.gridtech.core.util.cast
import net.gridtech.machine.controller.init.BootService
import net.gridtech.machine.model.*
import net.gridtech.machine.model.entity.Device
import net.gridtech.machine.model.entity.ModbusUnit
import net.gridtech.machine.model.entityField.CustomField
import net.gridtech.machine.model.property.entity.ModbusRead
import net.gridtech.machine.model.property.entity.ModbusWrite
import net.gridtech.machine.model.property.field.ValueDescription
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class ReadWriteService {
    @Autowired
    lateinit var bootService: BootService

    fun deviceCommandFieldValue(command: ModbusWrite): EntityFieldValue<ValueDescription>? =
            bootService.dataHolder.entityHolder[command.modbusUnitId]?.takeIf { it is ModbusUnit }?.let { modbusUnit ->
                cast<ModbusUnit>(modbusUnit)!!.getWritePointFieldValue(command.writePointId)
            }

    fun deviceCommandResultFieldValue(command: ModbusWrite): EntityFieldValue<ValueDescription>? =
            bootService.dataHolder.entityHolder[command.modbusUnitId]?.takeIf { it is ModbusUnit }?.let { modbusUnit ->
                cast<ModbusUnit>(modbusUnit)!!.getWritePointResultFieldValue(command.writePointId)
            }

    fun deviceStatusFieldValue(status: ModbusRead): EntityFieldValue<ValueDescription>? =
            bootService.dataHolder.entityHolder[status.modbusUnitId]?.takeIf { it is ModbusUnit }?.let { modbusUnit ->
                cast<ModbusUnit>(modbusUnit)!!.getReadPointFieldValue(status.readPointId)
            }
//    fun entityWriteFieldValue(entityWrite: EntityWrite):EntityFieldValue<ValueDescription>? =

    fun executeEntityWrite(entityWrite: EntityWrite, session: String) =
            when (entityWrite.targetType) {
                WriteTargetType.DEVICE_COMMAND -> {
                    bootService.dataHolder.entityHolder[entityWrite.entityId]?.takeIf { it is Device }?.let { device ->
                        cast<Device>(device)!!.getCommandById(entityWrite.targetId)
                                ?.let { command ->
                                    deviceCommandFieldValue(command)
                                }
                    }
                }
                WriteTargetType.CUSTOM_INPUT -> {
                    bootService.dataHolder.entityHolder[entityWrite.entityId]?.getCustomFieldValue(entityWrite.targetId)
                }
            }?.apply {
                bootService.dataHolder.entityFieldHolder[fieldId]?.let { cast<CustomField>(it) }?.let { field ->
                    field.description.value?.valueDescriptions?.find { it.id == entityWrite.valueDescriptionId }
                }?.let { valueDescription ->
                    update(valueDescription.valueExp, session)
                }
            }


    fun readConditionFieldValues(readCondition: ReadCondition): List<Pair<EntityRead, EntityFieldValue<ValueDescription>?>> =
            readCondition.reads.map { entityRead ->
                val customFieldValue = when (entityRead.targetType) {
                    ReadTargetType.DEVICE_STATUS -> {
                        bootService.dataHolder.entityHolder[entityRead.entityId]?.takeIf { it is Device }?.let { device ->
                            cast<Device>(device)!!.getStatusById(entityRead.targetId)
                                    ?.let { status ->
                                        deviceStatusFieldValue(status)
                                    }
                        }
                    }
                    ReadTargetType.CUSTOM_OUTPUT -> {
                        bootService.dataHolder.entityHolder[entityRead.entityId]?.getCustomFieldValue(entityRead.targetId)
                    }
                }
                entityRead to customFieldValue
            }

    fun readConditionValue(readCondition: ReadCondition): Boolean =
            readConditionFieldValues(readCondition).map { (entityRead, customFieldValue) ->
                if (entityRead.equals)
                    customFieldValue?.value?.id == entityRead.valueDescriptionId
                else
                    customFieldValue?.value?.id != entityRead.valueDescriptionId
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
                    bootService.dataHolder.getEntityByIdObservable<Device>(entityRead.entityId)
                            .flatMap { device ->
                                device.description.observable.switchMap {
                                    device.getStatusById(entityRead.targetId)?.let { modbusRead ->
                                        bootService.dataHolder.getEntityByIdObservable<ModbusUnit>(modbusRead.modbusUnitId)
                                                .flatMap { modbusUnit ->
                                                    modbusUnit.entityClass.description.observable
                                                            .switchMap { _ ->
                                                                modbusUnit.getReadPointFieldValue(modbusRead.readPointId)
                                                                        ?.observable?.map { it.id == entityRead.valueDescriptionId}
                                                                        ?: Observable.empty()
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