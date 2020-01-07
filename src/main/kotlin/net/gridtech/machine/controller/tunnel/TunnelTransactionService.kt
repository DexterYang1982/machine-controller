package net.gridtech.machine.controller.tunnel

import net.gridtech.core.util.cast
import net.gridtech.core.util.clone
import net.gridtech.machine.controller.init.BootService
import net.gridtech.machine.model.entity.Device
import net.gridtech.machine.model.entity.Tunnel
import net.gridtech.machine.model.entityField.CurrentTransaction
import net.gridtech.machine.model.entityField.ProcessRuntime
import net.gridtech.machine.model.entityField.ProcessState
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct

@Service
class TunnelTransactionService {

    @Autowired
    lateinit var bootService: BootService

    @PostConstruct
    fun start() {
        bootService.dataHolder.getEntityByConditionObservable { it is Tunnel }.map { cast<Tunnel>(it)!! }.subscribe { tunnel ->
            val currentTransaction = tunnel.entityClass.currentTransaction.getFieldValue(tunnel)
            currentTransaction.observable.subscribe {
                val transaction = clone(it)
                getNextProcessQueue(transaction)?.takeIf { queue -> queue.isNotEmpty() }?.apply {
                    bootService.dataHolder.getEntityByIdObservable<Device>(first().deviceId).subscribe { device ->
                        device.addNewProcessQueue(this)
                    }
                    currentTransaction.update(transaction, transaction.transactionSession)
                }
            }
        }
    }


    fun getNextProcessQueue(transaction: CurrentTransaction): List<ProcessRuntime>? {
        var queueStartIndex: Int? = null
        var queueEndIndex: Int? = null
        var queueDeviceId: String? = null
        for ((index, process) in transaction.transactionProcesses.withIndex()) {
            if (process.state == ProcessState.INIT) {
                if (queueStartIndex == null) {
                    queueStartIndex = index
                    queueEndIndex = index
                    queueDeviceId = process.deviceId
                    process.state = ProcessState.QUEUED
                } else if (queueDeviceId == process.deviceId) {
                    queueEndIndex = index
                    process.state = ProcessState.QUEUED
                } else {
                    break
                }
            } else if (process.state != ProcessState.FINISHED) {
                break
            }
        }
        return if (queueStartIndex != null && queueEndIndex != null) {
            transaction.transactionProcesses.subList(queueStartIndex, queueEndIndex+1)
        } else {
            null
        }
    }
}