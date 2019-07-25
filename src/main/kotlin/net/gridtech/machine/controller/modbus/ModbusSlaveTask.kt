package net.gridtech.machine.controller.modbus

import com.digitalpetri.modbus.master.ModbusTcpMaster
import com.digitalpetri.modbus.master.ModbusTcpMasterConfig
import com.digitalpetri.modbus.requests.*
import com.digitalpetri.modbus.responses.*
import io.netty.buffer.ByteBuf
import io.netty.util.ReferenceCountUtil
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import net.gridtech.core.data.IFieldValue
import net.gridtech.machine.model.EntityFieldValue
import net.gridtech.machine.model.entity.ModbusSlave
import net.gridtech.machine.model.entity.ModbusUnit
import net.gridtech.machine.model.property.entity.SlaveAddress
import net.gridtech.machine.model.property.entityClass.*
import net.gridtech.machine.model.property.field.ValueDescription
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ModbusSlaveTask(modbusSlave: ModbusSlave, modbusUnitObservable: Observable<ModbusUnit>) {
    private var currentConnectionStatus = false
    private val writeList = ArrayList<Triple<EntityFieldValue<ValueDescription>, WritePoint, IFieldValue>>()
    private val connection = modbusSlave.entityClass.connection.getFieldValue(modbusSlave)
    private val modbusUnits = ConcurrentHashMap<String, ModbusUnit>()
    private val disposables = ArrayList<Disposable>()
    private var master: ModbusTcpMaster? = null
    private val taskSubject = PublishSubject.create<Int>()

    init {
        disposables.add(modbusSlave.description.observable.subscribe { slaveAddress ->
            addressChanged(slaveAddress)
        })
        disposables.add(modbusUnitObservable.subscribe { modbusUnit ->
            addModbusUnit(modbusUnit)
        })
        disposables.add(Observable.interval(1000, 3000, TimeUnit.MILLISECONDS).subscribe {
            checkMasterStatus()
        })
        disposables.add(taskSubject.delay(100, TimeUnit.MILLISECONDS).subscribe {
            dealWithTask(it)
        })
    }

    private fun dealWithTask(lastReadIndex: Int) {
        if (currentConnectionStatus) {
            if (writeList.size > 0) {
                val (resultField, writePoint, commandFieldValue) = writeList.removeAt(0)
                master?.apply {
                    modbusWrite(this, writePoint, commandFieldValue).subscribe(
                            {
                                println("----------------")
                                resultField.update(CommandResult.ACCEPTED.name, commandFieldValue.session)
                                taskSubject.onNext(lastReadIndex)
                            },
                            {
                                resultField.update(CommandResult.EXCEPTION.name, commandFieldValue.session)
                                taskSubject.onNext(lastReadIndex)
                            })
                }
            } else {
                val readList =
                        modbusUnits.values.flatMap { modbusUnit ->
                            modbusUnit.entityClass.description.value!!.read.mapNotNull { readPoint ->
                                modbusUnit.getCustomFieldValue(readPoint.resultFieldId)?.let { Triple(modbusUnit, it, readPoint) }
                            }
                        }
                if (readList.isNotEmpty()) {
                    val newReadIndex = (lastReadIndex + 1) % readList.size
                    val (modbusUnit, resultField, readPoint) = readList[newReadIndex]
                    modbusRead(master!!, readPoint).subscribe(
                            { result ->
                                if (result != resultField.source?.value) {
                                    val session = followSession(modbusUnit, readPoint.sessionFollowWritePoints)
                                    resultField.update(result, session)
                                }
                                taskSubject.onNext(newReadIndex)
                            }, {
                        taskSubject.onNext(newReadIndex)
                    })
                } else {
                    taskSubject.onNext(lastReadIndex)
                }
            }
        }
    }

    private fun followSession(modbusUnit: ModbusUnit, followWritePoints: List<String>): String? =
            modbusUnit.entityClass.description.value?.write
                    ?.mapNotNull { writePoint ->
                        if (followWritePoints.contains(writePoint.id))
                            modbusUnit.getCustomFieldValue(writePoint.resultFieldId)?.source
                        else
                            null
                    }
                    ?.maxBy {
                        it.updateTime
                    }
                    ?.session


    private fun checkMasterStatus() {
        master?.connect()?.whenCompleteAsync { _, e ->
            val status = e == null
            if (status != connection.value) {
                connection.update(status)
            }
            if (currentConnectionStatus != status) {
                currentConnectionStatus = status
                if (currentConnectionStatus) {
                    taskSubject.onNext(0)
                }
            }
            if (e != null) {
                System.err.println(e.message)
            }
        }
    }

    private fun addModbusUnit(modbusUnit: ModbusUnit) {
        modbusUnits[modbusUnit.id] = modbusUnit
        disposables.add(
                modbusUnit.entityClass.description.observable
                        .switchMap { modbusUnitDescription ->
                            Observable.merge(
                                    modbusUnitDescription.write
                                            .map { writePoint ->
                                                val commandField = modbusUnit.getCustomFieldValue(writePoint.commandFieldId)!!
                                                val resultField = modbusUnit.getCustomFieldValue(writePoint.resultFieldId)!!
                                                commandField.observable
                                                        .filter {
                                                            commandField.session != resultField.session
                                                        }
                                                        .filter {
                                                            if (connection.value != true) {
                                                                resultField.update(CommandResult.OFFLINE.name, commandField.session)
                                                                false
                                                            } else if ((commandField.updateTime < System.currentTimeMillis() - writePoint.expired) &&
                                                                    writePoint.commandType == CommandType.INSTANT) {
                                                                resultField.update(CommandResult.EXPIRED.name, commandField.session)
                                                                false
                                                            } else
                                                                true
                                                        }.map {
                                                            Triple(resultField, writePoint, commandField.source!!)
                                                        }
                                            }
                            )
                        }
                        .subscribe {
                            writeList.add(it)
                        }
        )
        modbusUnit.onDelete().subscribe { _, _ ->
            modbusUnits.remove(modbusUnit.id)
        }
    }

    private fun addressChanged(slaveAddress: SlaveAddress) {
        master?.disconnect()
        master = ModbusTcpMaster(ModbusTcpMasterConfig.Builder(slaveAddress.ip).setPort(slaveAddress.port).build())
    }

    private fun modbusRead(modbusTcpMaster: ModbusTcpMaster, readPoint: ReadPoint): Single<String> =
            when (readPoint.point.memoryType) {
                MemoryType.HOLDING_REGISTER ->
                    Single.fromFuture(modbusTcpMaster.sendRequest<ReadHoldingRegistersResponse>(ReadHoldingRegistersRequest(readPoint.point.position - 1, readPoint.point.quantity), 1)).map {
                        val result = parseReadResult(it.registers, readPoint.point.quantity)
                        ReferenceCountUtil.release(it)
                        result
                    }
                MemoryType.COIL_STATUS ->
                    Single.fromFuture(modbusTcpMaster.sendRequest<ReadCoilsResponse>(ReadCoilsRequest(readPoint.point.position - 1, readPoint.point.quantity), 1)).map {
                        val result = it.coilStatus.readBoolean()
                        ReferenceCountUtil.release(it)
                        if (result) "1" else "0"
                    }
                MemoryType.INPUT_REGISTER ->
                    Single.fromFuture(modbusTcpMaster.sendRequest<ReadInputRegistersResponse>(ReadInputRegistersRequest(readPoint.point.position - 1, readPoint.point.quantity), 1)).map {
                        val result = parseReadResult(it.registers, readPoint.point.quantity)
                        ReferenceCountUtil.release(it)
                        result
                    }
                MemoryType.INPUT_STATUS ->
                    Single.fromFuture(modbusTcpMaster.sendRequest<ReadDiscreteInputsResponse>(ReadDiscreteInputsRequest(readPoint.point.position - 1, readPoint.point.quantity), 1)).map {
                        val result = it.inputStatus.readBoolean()
                        ReferenceCountUtil.release(it)
                        if (result) "1" else "0"
                    }
            }

    private fun modbusWrite(modbusTcpMaster: ModbusTcpMaster, writePoint: WritePoint, fieldValue: IFieldValue): Single<String> =
            when (writePoint.point.memoryType) {
                MemoryType.HOLDING_REGISTER -> {
                    val value = try {
                        Integer.parseInt(fieldValue.value)
                    } catch (e: Throwable) {
                        0
                    }
                    Single.fromFuture(modbusTcpMaster.sendRequest<WriteSingleRegisterResponse>(WriteSingleRegisterRequest(writePoint.point.position - 1, value), 1)).map {
                        ""
                    }
                }
                MemoryType.COIL_STATUS -> {
                    Single.fromFuture(modbusTcpMaster.sendRequest<WriteSingleCoilResponse>(WriteSingleCoilRequest(writePoint.point.position - 1, fieldValue.value == "1"), 1)).map {
                        ""
                    }
                }
                else -> Single.fromCallable { "" }
            }

    private fun parseReadResult(data: ByteBuf, quantity: Int): String {
        val list: MutableList<Short> = mutableListOf()
        for (index in 0 until quantity) {
            list.add(data.getShort(index * 2))
        }
        return list.joinToString(",")
    }

    fun stop() {
        disposables.forEach { it.dispose() }
        disposables.clear()
    }
}